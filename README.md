# Domino MCP Servlet

> **Domino MCP Toolkit** — Java HTTP gateway (MCP server) for HCL Domino.

---

## English

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
POST /mcp/servlet
Content-Type: application/json
```

| Method | Behavior |
|---|---|
| `initialize` | Legacy handshake (2025-06-18); serverInfo `Domino` (configurable via `mcpServerName` init-param / vConfig `SERVER_NAME`) v`2.3.0` |
| `server/discover` | Modern (2026-07-28): supported versions, capabilities, server identity |
| `tools/list` | Tool definitions from the agent (cached 60 s) |
| `tools/call` | Forwarded to the agent; its JSON is returned as the MCP tool result |
| `resources/list` / `resources/read` | Forwarded to the agent (cached 60 s); URIs referenced as `resource_link` in a read result are accepted for `resources/read` for the cache TTL (v2.3.0) |
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
curl -X POST "http://<domino-host>/mcp/servlet" \
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
| `target/domino-mcp-servlet.war` | copy to `<domino>/data/java/` |
| `target/domino-mcp-servlet.jar` | Alternative: JAR **and** `src/main/webapp/WEB-INF/web.xml` on the Domino Java classpath |

### Deployment on HCL Domino

1. Copy the WAR (or JAR + `web.xml`) to `<domino>/data/java/` — only **one** servlet definition per server (WAR **or** JAR)
2. `notes.ini`:
   ```ini
   HTTPJavaEnabled=1
   HTTPClassPath=<domino>/data/java
   ```
3. Restart the HTTP task: `tell http restart`
4. Test the endpoint: `POST /mcp/servlet` with `initialize` (see above)

### Versions & compatibility

| Component | Version |
|---|---|
| Domino MCP Servlet (this repo) | 2.3.0 |
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

**Recommendation:** Do not expose the endpoint unsecured to the public
internet — preferably run it behind an authenticating reverse proxy and
keep the ACL of `mcp_tools.nsf` restrictive.

Per-user Domino authentication (Basic-Auth at the Domino-10 level +
pass-through to the web agent + agent-side ACL check) is documented as an
implementation note in `docs/per-user-authentication.md`.

### License

Apache License 2.0 — see [LICENSE](LICENSE).

---

## Deutsch

Das **Domino MCP Servlet** macht Daten aus HCL Domino über den
[Model Context Protocol (MCP)](https://modelcontextprotocol.io) für LLM-Clients
(Claude, ChatGPT, …) ansprechbar. Die Servlet ist ein reines HTTP-Gateway:
Sie hält selbst keine Tools, verwendet keine Notes-API und leitet ausschließlich
an den LotusScript-Web-Agent `McpGateway` weiter.

### Bestandteile des Domino MCP Toolkit

| Komponente | Ort | Rolle |
|---|---|---|
| **Domino MCP Servlet** (dieses Repo) | GitHub | MCP-Protokoll-Endpoint (JSON-RPC 2.0 über HTTP) |
| **McpGateway** — Web-Agent + ScriptLibs + `mcp_tools.nsf` | OpenNTF.org | Tool-Registry und Tool-Implementierungen (LotusScript) |

> **Voraussetzung:** Die Notes-Komponente **Domino MCP Toolkit** (OpenNTF)
> muss auf dem **dieselben Domino-Server** installiert sein: Datenbank
> `mcp_tools.nsf` im Web-Root und Web-Agent `McpGateway` (`?open`-Trigger).
> Ohne sie antwortet die Servlet mit dem JSON-RPC-Fehler `MCP_1003`.

### MCP-Endpoint

```
POST /mcp/servlet
Content-Type: application/json
```

| Methode | Verhalten |
|---|---|
| `initialize` | Legacy-Handshake (2025-06-18); serverInfo `Domino` (configurable via `mcpServerName` init-param / vConfig `SERVER_NAME`) v`2.3.0` |
| `server/discover` | Modern (2026-07-28): unterstützte Versionen, Capabilities, Server-Identität |
| `tools/list` | Tool-Definitionen vom Agent (60 s gecacht) |
| `tools/call` | Weiterleitung an den Agent; dessen JSON wird als MCP-Tool-Result zurückgegeben |
| `resources/list` / `resources/read` | Weiterleitung an den Agent (60 s gecacht); per `resource_link` in einer Read-Antwort referenzierte URIs werden für `resources/read` für die Cache-TTL freigegeben (v2.3.0) |
| `prompts/list` / `prompts/get` | Weiterleitung an den Agent (60 s gecacht) |
| `notifications/*` | 202 quittiert, ohne Body |

#### Dual-Era: Legacy (2025-06-18) und Modern (2026-07-28)

Die Servlet spricht beide MCP-Ären am selben Endpoint. Die Ära wird pro
Request erkannt: `params._meta` mit
`io.modelcontextprotocol/protocolVersion` → **Modern**, sonst → **Legacy**
(Verhalten unverändert für Alt-Clients).

Modern-Requests (MCP 2026-07-28) sind stateless (kein `initialize`) und
verpflichten:

- `params._meta` mit `io.modelcontextprotocol/protocolVersion` **und**
  `io.modelcontextprotocol/clientCapabilities` (fehlend → HTTP 400 + `-32602`;
  unbekannte Version → 400 + `-32022` mit Liste der unterstützten Versionen)
- Header `MCP-Protocol-Version` (alle Requests), `Mcp-Method` (alle Requests)
  und `Mcp-Name` (`tools/call`, `resources/read`, `prompts/get`), gegen den
  Body validiert (Mismatch → 400 + `-32020`; `Mcp-Name` darf im Base64-Sentinel-
  Format `=?base64?...?=` kodiert sein)
- Alle Results tragen `resultType: "complete"` und
  `_meta.io.modelcontextprotocol/serverInfo`; List-/Read-/discover-Results
  zusätzlich `ttlMs` und `cacheScope: "private"` (Daten sind user-/ACL-abhängig)
- Unbekannte Methode → HTTP 404 + `-32601`; DELETE → 405

Beispiel:

```bash
curl -X POST "http://<domino-host>/mcp/servlet" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"query_view","arguments":{"rid":"<replicaID>","viewname":"(All)","count":25}}}'
```

### Konfiguration

Minimal. Die Servlet leitet die Agent-URL aus dem einkommenden Request ab:

```
{Scheme}://{Host}[:Port]/mcp_tools.nsf/McpGateway?open
```

Agent-Timeout: 30 s Default; konfigurierbar über das vConfig-Feld
`AGENT_TIMEOUT_MS` (Form `mMCPConfig` in `mcp_tools.nsf`, per `op=env`
gemeldet, 60-s-Cache — höchste Präzedenz) oder den web.xml-Init-Param
`mcpAgentTimeoutMs` (ms). Body-Guardrail: vConfig-Feld `MAX_PAYLOAD_BYTES`
(Standard 5 242 880 = 5 MB) oder web.xml-Init-Param `mcpMaxPayloadBytes`
(Fallback-Default 10 239 999 = gemessenes Server-HTTP-Limit abzgl. Puffer).
Präzedenz: vConfig > web.xml > eingebauter Default; Werte 1:1 (kein Clamp,
kein Plausibilitäts-Check); vConfig-Änderungen wirken nach ≤ 60 s
(Env-Cache). Server-Identität: `serverInfo.name` default `Domino`;
konfigurierbar über das vConfig-Feld `SERVER_NAME` (per `op=env`) oder den
web.xml-Init-Param `mcpServerName`. Pro Domino-Server setzen, damit mehrere
Server im MCP-Client unterscheidbar bleiben. Fehler des Agents
(`status: "error"`) werden als MCP-Result mit `isError: true` weitergereicht.

### Build

```bash
./build.sh            # Java 8, mvn clean + package (Tests werden übersprungen)
mvn clean package     # alternativ, inkl. Tests
```

Ergebnis:

| Artifact | Deployment |
|---|---|
| `target/domino-mcp-servlet.war` | nach `<domino>/data/java/` kopieren |
| `target/domino-mcp-servlet.jar` | Alternative: JAR **und** `src/main/webapp/WEB-INF/web.xml` in den Domino-Java-Classpath |

### Deployment auf HCL Domino

1. WAR (bzw. JAR + `web.xml`) nach `<domino>/data/java/` kopieren — nur **eine** Servlet-Definition je Server (WAR **oder** JAR)
2. `notes.ini`:
   ```ini
   HTTPJavaEnabled=1
   HTTPClassPath=<domino>/data/java
   ```
3. HTTP Task neu starten: `tell http restart`
4. Endpoint testen: `POST /mcp/servlet` mit `initialize` (s. o.)

### Versionen & Kompatibilität

| Komponente | Version |
|---|---|
| Domino MCP Servlet (dieses Repo) | 2.3.0 |
| Domino MCP Toolkit (OpenNTF) | siehe Release |
| MCP-Protokollversionen | `2026-07-28` (Modern), `2025-06-18` (Legacy) |

Schnittstellenvertrag (Request-/Response-Formate, Fehlercodes):
`AGENT_CONTRACT.md` im OpenNTF-Release **Domino MCP Toolkit**.

### Sicherheit

Zusätzlich wird der `Origin`-Header bei jedem POST geprüft
(DNS-Rebinding-Schutz): vorhandener, fremder `Origin` (anderes
Scheme/Host/Port) wird mit HTTP 403 abgewiesen; fehlender `Origin`
(typisch für Nicht-Browser-Clients) wird durchgelassen.

Die Servlet führt keine eigene Authentifizierung oder Autorisierung
durch — der Zugriffsschutz läuft vollständig über HCL Domino:
ACL der Datenbank `mcp_tools.nsf`, Zugriffsrechte des Web-Agenten
`McpGateway` und die Domino-Berechtigungen der anfragenden Nutzer.
Welche Daten abgerufen werden können, bestimmen die Tools, die in der
Notes-Komponente konfiguriert sind.

**Empfehlung:** Den Endpoint nicht ungesichert ans öffentliche Internet
geben — vorzugsweise hinter einem authentifizierenden Reverse-Proxy
betreiben und die ACL von `mcp_tools.nsf` restriktiv halten.

Per-User-Domino-Authentifizierung (Basic-Auth auf Domino-10-Ebene +
Durchreichung an den Web-Agent + Agent-seitige ACL-Prüfung) ist in
`docs/per-user-authentication.md` als Umsetzungsdokument beschrieben.

### Lizenz

Apache License 2.0 — siehe [LICENSE](LICENSE).

---

Domino, HCL und Lotus sind Marken von HCL Software, LLC.
Dieses Projekt ist nicht von HCL gesponsert, genehmigt oder unterstützt.
