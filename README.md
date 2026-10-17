# Domino MCP Servlet

> **Domino MCP Toolkit** — Java HTTP gateway (MCP server) for HCL Domino.

The **Domino MCP Servlet** exposes data from HCL Domino to LLM clients
(Claude, ChatGPT, …) via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io).
The servlet is a pure HTTP gateway: it holds no tools of its own, uses no
Notes API, and simply forwards everything to the LotusScript web agent
`McpGateway`.

### Components of the Domino MCP Toolkit

| Component | Location | Role |
|---|---|---|
| **Domino MCP Servlet** (this repo) | GitHub | MCP protocol endpoint (JSON-RPC 2.0 over HTTP) |
| **McpGateway** — web agent + ScriptLibs + `mcp_tools.nsf` | OpenNTF.org | Tool registry and tool implementations (LotusScript) |

> **Prerequisite:** The Notes component **Domino MCP Toolkit** (OpenNTF)
> must be installed on the **same Domino server**: the database
> `mcp_tools.nsf` in the web root and the web agent `McpGateway`
> (`?open` trigger). Without it, the servlet answers with the JSON-RPC
> error `MCP_1003`.

### MCP endpoint

```
POST /servlet/DominoMcpServlet
Content-Type: application/json
```

> **URL note:** `/servlet/DominoMcpServlet` is the recommended default mapping.
> The servlet is URL-agnostic (it derives the agent URL from the incoming
> request), so the admin may choose any other mapping:
> - **WAR deployment:** the mapping is taken from the WAR's
>   `WEB-INF/web.xml` (shipped with the default). Changing it requires
>   editing `web.xml` and **rebuilding the WAR**.
> - **JAR deployment:** the URL is freely chosen by the admin in the
>   `domino-web.xml` servlet registration — **no rebuild required**.

| Method | Behavior |
|---|---|
| `initialize` | Legacy handshake (2025-06-18); serverInfo `Domino` (configurable via `mcpServerName` init-param / vConfig `SERVER_NAME`) v`2.4.0` |
| `server/discover` | Modern (2026-07-28): supported versions, capabilities, server identity |
| `tools/list` | Tool definitions from the agent (cached 60 s) |
| `tools/call` | Forwarded to the agent; its JSON is returned as the MCP tool result |
| `resources/list` / `resources/read` | Forwarded to the agent (cached 60 s); URIs referenced as `resource_link` in a read result are accepted for `resources/read` for the cache TTL (v2.3.0). The `resource_link` items themselves are filtered out of the client response before forwarding (v2.4.0) — strict MCP clients (e.g. opencode) reject `resources/read` responses containing `resource_link` items; the unlock is applied before the filter and stays effective |
| `prompts/list` / `prompts/get` | Forwarded to the agent (cached 60 s) |
| `notifications/*` | Acknowledged with 202, no body |

### Dual-era: Legacy (2025-06-18) and Modern (2026-07-28)

The servlet speaks both MCP eras on the same endpoint. The era is detected
per request: `params._meta` with
`io.modelcontextprotocol/protocolVersion` → **Modern**, otherwise → **Legacy**
(behavior unchanged for legacy clients).

Modern requests (MCP 2026-07-28) are stateless (no `initialize`) and
require:

- `params._meta` with `io.modelcontextprotocol/protocolVersion` **and**
  `io.modelcontextprotocol/clientCapabilities` (missing → HTTP 400 + `-32602`;
  unknown version → 400 + `-32022` with the list of supported versions)
- Header `MCP-Protocol-Version` (all requests), `Mcp-Method` (all requests)
  and `Mcp-Name` (`tools/call`, `resources/read`, `prompts/get`), validated
  against the body (mismatch → 400 + `-32020`; `Mcp-Name` may be encoded in
  the Base64 sentinel format `=?base64?...?=`)
- All results carry `resultType: "complete"` and
  `_meta.io.modelcontextprotocol/serverInfo`; list/read/discover results
  additionally carry `ttlMs` and `cacheScope: "private"` (data is
  user/ACL-dependent)
- Unknown method → HTTP 404 + `-32601`; DELETE → 405

Example:

```bash
curl -X POST "http://<domino-host>/servlet/DominoMcpServlet" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"query_view","arguments":{"rid":"<replicaID>","viewname":"(All)","count":25}}}'
```

### Configuration

Minimal. The servlet derives the agent URL from the incoming request:

```
{Scheme}://{Host}[:Port]/mcp_tools.nsf/McpGateway?open
```

Agent timeout: 30 s default; configurable via the vConfig field
`AGENT_TIMEOUT_MS` (form `mMCPConfig` in `mcp_tools.nsf`, reported via
`op=env`, 60 s cache — highest precedence) or the web.xml init-param
`mcpAgentTimeoutMs` (ms). Body-size guardrail: vConfig field
`MAX_PAYLOAD_BYTES` (default 5 242 880 = 5 MB) or the web.xml init-param
`mcpMaxPayloadBytes` (fallback default 10 239 999 = measured server HTTP
limit minus buffer). Precedence: vConfig > web.xml > built-in default;
values are applied 1:1 (no clamping, no plausibility check); vConfig
changes take effect after at most 60 s (env cache). Server identity:
`serverInfo.name` defaults to `Domino`; configurable via the vConfig field
`SERVER_NAME` (reported via `op=env`) or the web.xml init-param
`mcpServerName`. Set it per Domino server to keep multiple servers
distinguishable in the MCP client. Agent errors
(`status: "error"`) are forwarded as an MCP result with `isError: true`.

### Build

```bash
./build.sh            # Java 8, mvn clean + package (tests are skipped)
mvn clean package     # alternatively, including tests
```

Output:

| Artifact | Deployment |
|---|---|
| `target/domino-mcp-servlet.war` | copy to `<domino>/data/servlet/` |
| `target/domino-mcp-servlet.jar` | Alternative: JAR **and** `src/main/webapp/WEB-INF/web.xml` into the Domino Java classpath directory |

### Deployment on HCL Domino

1. Copy the WAR (or JAR + `web.xml`) to the Java classpath directory configured in the server document, e.g. `<domino>/data/servlet/` — only **one** servlet definition per server (WAR **or** JAR)
2. Server document *CN=Server* (open it in the Notes client — e.g. from the address book — or in the Administration Client → "Internet Protocols" tab → "Domino Web Server" → "Java Servlets" section):
   - ☑ check **Java servlet support**
   - **Java servlet classpath:** e.g. `servlet` (relative to the Domino data directory; the JAR/WAR must be placed in this directory — the path is freely configurable)
   - **Java servlet path:** `/servlet`
3. Restart the HTTP task: `tell http restart`
4. Test the endpoint: `POST /servlet/DominoMcpServlet` with `initialize` (see above)

### Versions & compatibility

| Component | Version |
|---|---|
| Domino MCP Servlet (this repo) | 2.4.0 |
| Domino MCP Toolkit (OpenNTF) | see release |
| MCP protocol versions | `2026-07-28` (Modern), `2025-06-18` (Legacy) |

Interface contract (request/response formats, error codes):
`AGENT_CONTRACT.md` in the OpenNTF release **Domino MCP Toolkit**.

### Security

In addition, the `Origin` header is checked on every POST
(DNS-rebinding protection): a present, foreign `Origin` (different
scheme/host/port) is rejected with HTTP 403; a missing `Origin`
(typical for non-browser clients) is passed through.

The servlet performs no authentication or authorization of its own — access
control is handled entirely by HCL Domino: the ACL of the database
`mcp_tools.nsf`, the access rights of the web agent `McpGateway`, and the
Domino permissions of the requesting user. Which data can be retrieved is
determined by the tools configured in the Notes component.

Per-user Domino authentication (Basic-Auth at the Domino-10 level +
pass-through to the web agent + agent-side ACL check) is documented as an
implementation note in
[docs/per-user-authentication.md](https://github.com/jBubbleboy/domino-mcp-servlet/blob/main/docs/per-user-authentication.md).

### License

Apache License 2.0 — see [LICENSE](LICENSE).

### Support & consulting

This project is free to use (Apache License 2.0).

- **GitHub Sponsors:** optional support for the project; what each tier
  includes is listed on the [GitHub Sponsors page](https://github.com/sponsors/jBubbleboy).
- **Consulting & support:** individual engagements (setup, integration,
  custom extensions) are arranged directly with the author.
  Contact:
  [LinkedIn](https://www.linkedin.com/in/erik-schmalz-55b89320) or
  [XING](https://www.xing.com/profile/Erik_Schmalz2)

Domino, HCL and Lotus, and their logos, are trademarks of HCL Software, LLC.
This project is not sponsored, endorsed, or supported by HCL.
