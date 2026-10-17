# Per-User Domino Authentication for the MCP Gateway

**Status:** Design & implementation document (as of 2026-09-15)
**Goal:** Each MCP client retains its own Domino identity; data queries run with the Domino ACL rights of the calling user.

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
   │  • invalid credentials → 401 or the Domino HTML login page (HTTP 200,
   │    depending on the server's HTTP-authentication settings)
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
    - Servlet gate: unauthenticated external request → internal agent call without credentials → `mcp_tools.nsf` ACL blocks Anonymous (401 or HTML login page) → the agent answers `MCP_1003`/`MCP_1004`.
   - Agent ACL check: the user's AccessLevel is checked per target DB.

### 1.3 Known limitations (accepted)

- **No HTTPS:** Basic-Auth travels in cleartext over the LAN (192.168.x.x). Mitigation: dedicated low-privilege IDs, internal LAN only, no internet exposure (see section 7).
- **Tool/resource/prompt lists** are cached 60 s in the servlet and are user-independent (metadata from `mcp_tools.nsf`, no user data) — a shared cache is acceptable.
- `initialize` and `server/discover` remain anonymously reachable (they only expose server identity/capabilities, no data).
- A password change requires a config update in the respective client.

---

## 2. Server administration (reference deployment: HCL Domino 10, 192.168.x.x)

> The HTTP servlet requires Java 8 (Java 1.8); HCL Domino 10 and later ship a
> compatible JRE, so this is guaranteed on those versions. The steps are
> written generically for any Domino-10+ server; IP addresses and sample
> data (usernames, replica IDs) are shown only for the reference deployment.

### 2.1 Enable Basic Authentication (name-and-password)

Per the HCL procedure, three steps are required (see the HCL reference at the
end of this section):

1. **Enable name-and-password authentication** — either in the **server
   document** (Ports → Internet Ports → Web → HTTP: "Name & password" = Yes
   for TCP and, if applicable, SSL) or, if the server uses Internet Sites, in
   the **Internet Site document** (Security → TCP/SSL Authentication →
   Name & password = Yes). A **WebRealm** document in the directory decides
   which directory database the authentication realm is validated against
   (reference deployment: realm `pwd` → `names.nsf`).
   - The MCP toolkit is server-agnostic: which directory DB is used is
     decided per Domino server — it is the directory database that this
     server fundamentally works with.
2. **Person documents with Internet passwords** in the Domino directory: the
   **Internet password** of the Person document is what Basic-Auth validates
   against (not necessarily the Notes ID password). Set it for each MCP user.
3. **Database ACLs**: remove **Anonymous**/**Everyone** from `mcp_tools.nsf`
   and grant the users Reader (2.2).

**Keep anonymous access enabled globally** (other existing web resources must
keep working). The protection for the MCP endpoint comes from the
`mcp_tools.nsf` ACL (2.2) and the servlet gate (3.2).

After saving: **restart the HTTP task**:

```
tell http restart
```

> HCL reference: [Setting up basic name-and-password authentication —
> Domino 10.0.1 Administrator Help](https://help.hcl-software.com/domino/10.0.1/admin/conf_settingupbasicnameandpasswordauthentication_t.html)

### 2.2 ACL of `mcp_tools.nsf`

- Remove **Anonymous** and **Everyone** from all access levels (or set to "None").
- Effect: the internal agent call without validated credentials is rejected by the HTTP task (**401**, or the Domino **HTML login page** with HTTP 200 depending on the server settings); the agent does not deliver data.
- The MCP users (or their dedicated IDs) need **Reader** on `mcp_tools.nsf` (to read the tool list) — otherwise 401 as well.

Every request to `mcp_tools.nsf` involves **two separate questions**: which
*caller* identity the HTTP task checks against the database ACL, and under which
*effective user* the agent code itself runs. The `McpGateway` agent option
"Run as web user" is what ties the two together. The three caller levels:

| Caller level (checked against the `mcp_tools.nsf` ACL on every request) | Execution identity (effective user) of the agent code |
|---|---|
| 1. **Anonymous** — no or invalid Basic-Auth credentials | **Server ID** (agent signer) — same as level 2; `Session.UserName` = "Anonymous". The Anonymous/Everyone ACL row decides whether the call reaches the agent at all; if it is allowed, that access level (Reader/Editor/Designer, …) also bounds what anonymous callers can do on this database — a blocked row → 401 / HTML login page |
| 2. **Authenticated user** — valid Basic-Auth credentials | **Server ID** (agent signer); `Session.UserName` = the calling user. The caller needs at least a non-None ACL level to reach the agent at all (Reader suffices — verified live, 2.4); the agent's own database operations then run under the signer's (server ID's) rights, so per-user separation on the *target* databases still requires the agent-side AccessLevel checks (section 4) |
| 3. **User ID (invoker)** — the agent option **"Run as web user"** is checked | the **invoking user** (browser login name). Domino then checks the invoker's rights in the database ACL instead of the signer's, and the agent code runs with the invoker's access rights on all databases it touches — the target-database ACLs are enforced natively per request. An unauthenticated invoker runs the agent under **Anonymous** (i.e. with Anonymous' rights); the invoker must therefore hold direct access rights on **every** target database and on `mcp_tools.nsf` itself |

Levels 1 and 2 differ only in whether the caller is authenticated — in both
cases the agent code runs under the server ID. Only level 3 uses the calling
user's access rights for the agent's own execution. See section 4 ("Run as web
user (alternative)") for the trade-offs.

### 2.3 Dedicated Domino IDs for MCP access

- Create a **separate ID** per MCP user (or per logical role) — never the server admin ID.
- The normal Domino ACL assignment of these IDs determines which databases/tools are visible for MCP access.
- Recommended: IDs with minimal mail/DB association, referenced only to the required databases.

### 2.4 Verification (admin level)

Verified live (2026-10-15, reference deployment `192.168.x.x`: Basic-Auth
enabled via the default Internet Site, WebRealm `pwd` → `names.nsf`,
`mcp_tools.nsf` ACL without Anonymous/Everyone, user with Reader):

```bash
# Without credentials: servlet reachable (anonymous), the internal agent call
# is answered with the Domino HTML login page, so the agent cannot parse JSON:
curl -i -X POST http://192.168.x.x/servlet/DominoMcpServlet \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
# Observed: HTTP 200 + JSON-RPC error MCP_1003 "Notes agent unreachable"
# (data: Unexpected character '<' — the HTML login page) — NO tools.
# MCP_1004 (-32003, see 3.3) only appears when the internal call returns a
# clean HTTP 401/403.

# With credentials (Domino name + Internet password of the Person document):
curl -i -u 'mcp_user:mypass' -X POST http://192.168.x.x/servlet/DominoMcpServlet \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
# Observed: HTTP 200 + {"result":{"tools":[...]}} — proves the full chain:
# the HTTP task validates Basic-Auth (getRemoteUser() set), the servlet gate
# forwards the Authorization header, the agent runs with the user's ACL rights.

# Wrong credentials:
curl -i -u 'mcp_user:WRONG' -X POST ...
# Observed: HTTP 200 + the Domino HTML login page (/names.nsf?Login) —
# Domino's default behavior (the servlet is not executed). A raw HTTP 401 is
# only returned if the server is configured to answer 401 for invalid
# Basic auth — check the HTTP authentication settings of your server release.
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

- `pom.xml` + `SERVER_VERSION`: this feature shipped as **2.2.0** (new feature, no breaking change for legacy clients — without credentials the servlet behaves as before: an anonymous agent call that then fails at the `mcp_tools.nsf` ACL); the current release is **2.4.0** (further features, see README).
- Build: `./build.sh`; copy the WAR to the Java classpath directory configured in the server document (e.g. `<domino>/data/servlet/`); enable **Java servlet support** there (tab "Internet Protocols" → "Domino Web Server" → "Java Servlets"); `tell http restart`.
- URL mapping: default `/servlet/DominoMcpServlet`. **WAR:** the mapping comes from the WAR's `web.xml` — a change requires editing `web.xml` and a **new build**. **JAR:** the admin chooses the URL freely in the `domino-web.xml` registration — **no build required** (the servlet is URL-agnostic and derives the agent URL from the incoming request).

---

## 4. Web agent `McpGateway` (interface requirements)

The agent implementation is part of the Domino MCP Toolkit (OpenNTF); this section only fixes the interface the servlet expects from it.

The web agent runs under the server ID and therefore has server-level access to everything. `Session.UserName` is only an informational field: unless the agent checks the calling user's Domino ACLs itself, the per-user separation is not effective.

**Run as web user (alternative):** the agent option "Run as web user"
(„Als Webbenutzer ausführen") is **not** set in the reference deployment
(verified in the agent DXL: no web-user attribute). If you want the agent to
run with the **calling user's rights** instead, check that option on the
`McpGateway` agent: then Domino enforces the target database ACLs natively
per request — but the user must hold direct access rights on every target
database (metadata/registry operations then also run under the user, so the
user still needs Reader on `mcp_tools.nsf`).

### 4.1 Requirements

- Every operation that addresses a target database by `rid` (replica ID) must check the user's access level on that database before delivering data.
- Operations that do not address a target database (registry reads, released folders) are gated by the `mcp_tools.nsf` ACL alone.
- Operations that execute server-side code (`run_agent`, `run_console`) are not bounded by the user's DB access level — the agent runs under the server ID. Their only guard is the whitelist configured in the `mcp_tools.nsf` registry (allowed agents per DB, allowed console commands).
- On denial, the agent prints nothing except the error JSON.
- If `Session.UserName` is `Anonymous` or empty, the agent rejects the request with the error format from 4.2.

### 4.2 Error format

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

Generate the token once (the user part is the Domino name — full DN, short
name, or e-mail address from the Person document):

```bash
echo -n '<domino_user>:<domino_pass>' | base64
# → Authorization: Basic <output>
```

**Immediate action:**

```bash
chmod 600 ~/.config/opencode/opencode.json
```

The file is currently `644` (world-readable) and already contains LLM API keys. With Domino credentials this becomes more critical.

- Each MCP user uses **their own** config with **their own** Domino credentials.
- OAuth auto-detection plays no role here (the endpoint does not return a 401 + `WWW-Authenticate` challenge in the MCP sense; wrong credentials yield the Domino HTML login page (HTTP 200), which the client reports as a connection error because it cannot parse the response as JSON-RPC).

---

## 6. Expected behavior (matrix)

| Scenario | Result |
|---|---|
| No `Authorization` header | Request reaches the servlet (anonymous), gate → internal call without credentials → `mcp_tools.nsf` ACL blocks it (HTML login page) → JSON-RPC error `MCP_1003` (observed 2026-10-15; `MCP_1004` only on a clean 401/403) |
| Wrong credentials | **HTTP 200 + Domino HTML login page** (or 401 if so configured) from the HTTP task, the servlet is not executed |
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


