# Per-User Domino Authentication for the MCP Gateway

**Status:** Design & implementation document (as of 2026-09-15)
**Goal:** Each MCP client retains its own Domino identity; data queries run with the Domino ACL rights of the calling user.

> **Deutsch (Kurzfassung):** Jeder MCP-Client behält seine eigene Domino-Identität. Domino
> authentifiziert (Basic-Auth im HTTP-Task); die Servlet leitet nur den vom Domino-Task
> validierten `Authorization`-Header an den internen Agent-Call weiter — und nur dann, wenn
> `getRemoteUser()` gesetzt ist (Anti-Spoofing). Der Web-Agent läuft unter der Server-ID und
> muss die User-ACLs (`AccessLevel`) daher selbst pro Ziel-DB prüfen. Agent-401/403 werden als
> `MCP_1004` (`-32003`) gemeldet. Details siehe den englischen Abschnitt unten.

---

## 1. Goal and overall design

### 1.1 Request flow

```
MCP client (e.g. opencode)
   │  POST /servlet/DominoMcpServlet
   │  Header: Authorization: Basic base64(domino_user:domino_pass)
   ▼
Domino HTTP task                          ← AUTHENTICATION (before the servlet)
   │  • validates Basic-Auth against the directory database
   │  • invalid/missing credentials + protected resource → HTTP 401
   │  • success → the user is known to the servlet (getRemoteUser())
   ▼
DominoMcpServlet (Java, pure gateway)
   │  • forwards the (Domino-validated) Authorization header
   │  • Gate: only when getRemoteUser() != null (anti-spoofing)
   ▼
internal HTTP POST  {same-host}/mcp_tools.nsf/McpGateway?open
   │  • validated again by the HTTP task
   ▼
McpGateway web agent (LotusScript)
   • always runs under the SERVER ID (Domino behavior for web agents)
   • Session.UserName = the authenticated user (else "Anonymous")
   • MUST check the user ACLs itself (AccessLevel check per target DB)
```

### 1.2 Basic principles

1. **Domino authenticates, the servlet passes through.** The servlet implements no auth logic of its own; it only passes the already-validated `Authorization` header from the Domino HTTP task to the internal agent call.
2. **The web agent is the real data protection line.** Web agents run under the server ID and therefore have server access to everything. Without an agent-side `AccessLevel` check, the per-user separation is not effective.
3. **Two enforcement points (defense in depth):**
   - Servlet gate: unauthenticated external request → internal agent call without credentials → `mcp_tools.nsf` ACL blocks Anonymous → 401.
   - Agent ACL check: the user's AccessLevel is checked per target DB.

### 1.3 Known limitations (accepted)

- **No HTTPS:** Basic-Auth travels in cleartext over the LAN (192.168.x.x). Mitigation: dedicated low-privilege IDs, internal LAN only, no internet exposure (see section 7).
- **Tool/resource/prompt lists** are cached 60 s in the servlet and are user-independent (metadata from `mcp_tools.nsf`, no user data) — a shared cache is acceptable.
- `initialize` and `server/discover` remain anonymously reachable (they only expose server identity/capabilities, no data).
- A password change requires a config update in the respective client.

---

## 2. Domino-10 administration (reference deployment: 192.168.x.x)

> The steps are written generically for any Domino-10 server; IP addresses and
> sample data (usernames, replica IDs) are shown only for the reference deployment.

### 2.1 Enable Basic Authentication

1. Open the **Domino server document**.
2. **Configuration → Internet → Web Authentication** (the exact label may vary slightly per Domino-10 patch):
   - Check: **Basic Authentication**
   - **Authentication database:** the directory database configured on the respective server (the server directory, e.g. `names.nsf` or its replica).
     - The MCP toolkit is server-agnostic: which directory DB to enter here is decided per Domino server — it is the directory database that this server fundamentally works with.
3. **Keep anonymous access enabled globally** (other existing web resources must keep working). The protection for the MCP endpoint comes from the `mcp_tools.nsf` ACL (2.2) and the servlet gate (3.2).
4. Save the server document; **restart the HTTP task**:
   ```
   tell http restart
   ```

### 2.2 ACL of `mcp_tools.nsf`

- Remove **Anonymous** and **Everyone** from all access levels (or set to "None").
- Effect: the internal agent call without validated credentials is answered with **401** by the HTTP task; the agent does not run.
- The MCP users (or their dedicated IDs) need **Reader** on `mcp_tools.nsf` (to read the tool list) — otherwise 401 as well.

### 2.3 Dedicated Domino IDs for MCP access

- Create a **separate ID** per MCP user (or per logical role) — never the server admin ID.
- The normal Domino ACL assignment of these IDs determines which databases/tools are visible for MCP access.
- Recommended: IDs with minimal mail/DB association, referenced only to the required databases.

### 2.4 Verification (admin level)

```bash
# Without credentials: servlet reachable (anonymous), the internal agent call must return 401
curl -i -X POST http://192.168.x.x/servlet/DominoMcpServlet \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
# Expected: HTTP 200 + JSON-RPC error (MCP_1003 / MCP_1004, see 3.3) — NO tools

# With credentials:
curl -i -u 'mcp_user:mypass' -X POST http://192.168.x.x/servlet/DominoMcpServlet \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
# Expected: HTTP 200 + {"result":{"tools":[...]}}

# Wrong credentials:
curl -i -u 'mcp_user:WRONG' -X POST ...
# Expected: raw HTTP 401 from the Domino HTTP task (the servlet is not executed)
```

---

## 3. Servlet changes (this repo)

**Principle:** Minimal, thread-safe change; Java 8; no new dependency; no Notes API.

### 3.1 `NotesAgentClient.java`

Header field + forwarding in `doPost()` (currently line 172–193, where only `Accept` and `Content-Type` are set):

```java
public class NotesAgentClient {

    /** Only Domino-HTTP-task-validated credentials; null = anonymous agent call. */
    private final String authorizationHeader;

    public NotesAgentClient() { this(null); }

    public NotesAgentClient(String authorizationHeader) {
        this.authorizationHeader = authorizationHeader;
    }

    // ... in doPost(), after con.setRequestProperty("Content-Type", ...):
    if (authorizationHeader != null) {
        con.setRequestProperty("Authorization", authorizationHeader);
    }
}
```

- **Whitelist principle:** only `Authorization` is forwarded — no other incoming headers, no `Cookie`.
- All 6 public methods (`fetchToolList`, `callTool`, `fetchResourceList`, `readResource`, `fetchPromptList`, `getPrompt`) keep their signatures unchanged; the header is provided per instance.

### 3.2 `DominoMcpServlet.java`

**Security gate** (package-private static → unit-testable with Mockito):

```java
/**
 * Returns the Authorization header for the internal agent call — but ONLY
 * if the Domino HTTP task actually authenticated the request
 * (getRemoteUser() is then set). Otherwise null: an anonymous
 * external client must not push foreign credentials against the agent
 * (anti-spoofing).
 */
static String authForAgentCall(HttpServletRequest req) {
    if (req.getRemoteUser() == null) {
        return null;
    }
    String h = req.getHeader("Authorization");
    return (h != null && !h.trim().isEmpty()) ? h.trim() : null;
}
```

**Per-request client** in `doPost()` (currently line 195 ff.; the `agentClient` field at line 166 is removed):

```java
// in doPost(), after method detection:
final NotesAgentClient reqAgentClient =
        new NotesAgentClient(authForAgentCall(req));
```

- `reqAgentClient` is passed to the 5 handlers (`handleToolsList`, `handleToolsCall`, `handleResourcesList`, `handleResourcesRead`, `handlePromptsList`, `handlePromptsGet`): signatures extended with a `NotesAgentClient client` parameter; internal calls `agentClient.xxx(...)` → `client.xxx(...)`.
- The registry classes (`ToolRegistry`, `ResourceRegistry`, `PromptRegistry`) stay **unchanged** — they already receive the client as a parameter.
- Thread safety: one client object per request, no mutable fields.

### 3.3 Error mapping for 401/403

Today: agent-401 → generic `MCP_1003` (`sendAgentError`, line 950). New distinction:

```java
private static final int ERR_AUTH_REQUIRED = -32003;  // MCP_1004

private void sendAgentError(HttpServletResponse resp, JsonNode id, IOException e)
        throws IOException {
    if (e instanceof NotesAgentException) {
        int status = ((NotesAgentException) e).getHttpStatus();
        if (status == 401) {
            sendError(resp, ERR_AUTH_REQUIRED, "MCP_1004: No valid Domino authentication",
                    "The calling user is not authenticated or has no access to mcp_tools.nsf", id);
            return;
        }
        if (status == 403) {
            sendError(resp, ERR_AUTH_REQUIRED, "MCP_1004: Access denied",
                    "The calling user does not have sufficient privileges on mcp_tools.nsf", id);
            return;
        }
    }
    sendError(resp, ERR_AGENT_UNREACHABLE, "MCP_1003: Notes agent unreachable",
            String.valueOf(e.getMessage()), id);
}
```

- The JSON-RPC status stays `200` (legacy behavior of the servlet); the code `-32003` signals the auth failure to the client.

### 3.4 Tests (existing Jetty + `AgentStubServer` setup)

1. **Forwarding:** the stub logs received request headers → with a set `Authorization` + a set `remoteUser`, the header must arrive 1:1 at the agent.
2. **Gate:** a set `Authorization` header BUT `remoteUser == null` → the agent receives **no** `Authorization` header.
3. **401 mapping:** the stub answers `401` → JSON-RPC error `-32003` / `MCP_1004`.
4. **Regression:** `mvn clean package` (incl. tests) green, Java 8.

### 3.5 Versioning & deployment

- `pom.xml` + `SERVER_VERSION`: this feature shipped as **2.2.0** (new feature, no breaking change for legacy clients — without credentials the servlet behaves as before: an anonymous agent call that then fails at the `mcp_tools.nsf` ACL); the current release is **2.3.0** (further features, see README).
- Build: `./build.sh`; copy the WAR to `<domino>/data/java/`; `tell http restart`.

---

## 4. Web agent `McpGateway` (interface requirements)

The agent implementation is part of the Domino MCP Toolkit (OpenNTF); this section only fixes the interface the servlet expects from it.

The web agent runs under the server ID and therefore has server-level access to everything. `Session.UserName` is only an informational field: unless the agent checks the calling user's Domino ACLs itself, the per-user separation is not effective.

### 4.1 Requirements

- Every operation that addresses a target database by `rid` (replica ID) must check the user's access level on that database before delivering data.
- On denial, the agent prints nothing except the error JSON.
- If `Session.UserName` is `Anonymous` or empty, the agent rejects the request with the error format from 4.3.

### 4.2 Minimum access level per operation

| Operation | Tools | Minimum level |
|---|---|---|
| Tool metadata | `list` (metadata from `mcp_tools.nsf`) | Reader on `mcp_tools.nsf` |
| Read DB metadata | `list_databases`, `list_views`, `list_forms` | Reader on the target DB |
| Read document/view | `query_view`, `read_document` | Reader |
| Read resource/prompt | `resread`, `promptget` | Reader |
| Create document | `create_document` | Author |
| Update document | `update_document` | Author |

### 4.3 Error format

The agent reports denials as JSON with `status: "error"` and the code `MCP_AUTH` (no authenticated user) or `MCP_ACL` (access level on the target database too low). The servlet forwards such a result as an MCP tool result with `isError: true`. HTTP 401/403 of the internal agent call are mapped to `MCP_1004` (-32003), see 3.3.

---

## 5. MCP client (opencode)

`~/.config/opencode/opencode.json`:

```json
"domino": {
  "type": "remote",
  "url": "http://localhost/servlet/DominoMcpServlet",
  "headers": {
    "Authorization": "Basic <base64(<domino_user>:<domino_pass>)>"
  },
  "enabled": true
}
```

(`base64("<domino_user>:<domino_pass>")` — the sample value is made up.)

**Immediate action:**

```bash
chmod 600 ~/.config/opencode/opencode.json
```

The file is currently `644` (world-readable) and already contains LLM API keys. With Domino credentials this becomes more critical.

- Each MCP user uses **their own** config with **their own** Domino credentials.
- OAuth auto-detection plays no role here (the endpoint does not return a 401 + `WWW-Authenticate` challenge in the MCP sense; the raw HTTP 401 for wrong credentials is reported by the client as a connection error).

---

## 6. Expected behavior (matrix)

| Scenario | Result |
|---|---|
| No `Authorization` header | Request reaches the servlet (anonymous), gate → internal call without credentials → `mcp_tools.nsf` ACL 401 → JSON-RPC error `MCP_1004` (-32003) |
| Wrong credentials | **Raw HTTP 401** from the Domino HTTP task, the servlet is not executed |
| Correct credentials, but no Reader on `mcp_tools.nsf` | Internal call 401/403 → `MCP_1004` |
| Correct credentials + Reader on target DB | Data is delivered |
| Correct credentials, but no Reader/Author on target DB | Agent JSON `MCP_ACL` → MCP result `isError: true` |
| `initialize` / `server/discover` without credentials | works (metadata, no user data) |

---

## 7. Security risks and mitigation

| Risk | Assessment / mitigation |
|---|---|
| Basic-Auth in cleartext (no HTTPS) | Dedicated low-privilege IDs; endpoint only in the trusted LAN (192.168.x.x); no public exposure. Long-term: TLS on the Domino HTTP task or a reverse proxy with TLS termination. |
| Cleartext credentials in `opencode.json` | `chmod 600`; dedicated IDs with limited ACL; rotation on personnel change. |
| Web agent runs under the server ID | **Required:** agent-side AccessLevel checks (section 4). Without them the per-user separation is ineffective — this is the central protection line. |
| Shared tool-list cache (60 s) | Accepted: only metadata from `mcp_tools.nsf`, no user data. |
| Spoofing: anonymous + foreign `Authorization` header | The servlet gate (`getRemoteUser() == null` → no forwarding) prevents an anonymous client from using foreign credentials against the internal agent call. |
| Password reset on Domino | Client configs must be updated (per affected user). |


