# Domino MCP Servlet

> **Domino MCP Toolkit** — Java-HTTP-Gateway (MCP-Server) für HCL Domino.
> MCP Gateway servlet for HCL Domino — part of the Domino MCP Toolkit.

Das **Domino MCP Servlet** macht Daten aus HCL Domino über den
[Model Context Protocol (MCP)](https://modelcontextprotocol.io) für LLM-Clients
(Claude, ChatGPT, …) ansprechbar. Die Servlet ist ein reines HTTP-Gateway:
Sie hält selbst keine Tools, verwendet keine Notes-API und leitet ausschließlich
an den LotusScript-Web-Agent `McpGateway` weiter.

## Bestandteile des Domino MCP Toolkit

| Komponente | Ort | Rolle |
|---|---|---|
| **Domino MCP Servlet** (dieses Repo) | GitHub | MCP-Protokoll-Endpoint (JSON-RPC 2.0 über HTTP) |
| **McpGateway** — Web-Agent + ScriptLibs + `mcp_tools.nsf` | OpenNTF.org | Tool-Registry und Tool-Implementierungen (LotusScript) |

> **Voraussetzung:** Die Notes-Komponente **Domino MCP Toolkit** (OpenNTF)
> muss auf dem **dieselben Domino-Server** installiert sein: Datenbank
> `mcp_tools.nsf` im Web-Root und Web-Agent `McpGateway` (`?open`-Trigger).
> Ohne sie antwortet die Servlet mit dem JSON-RPC-Fehler `MCP_1003`.

## MCP-Endpoint

```
POST /mcp/servlet
Content-Type: application/json
```

| Methode | Verhalten |
|---|---|
| `initialize` | Protokoll-Handshake; serverInfo `domino-mcp-server` v`2.0.0`, Default-Protokoll `2025-06-18` |
| `tools/list` | Tool-Definitionen vom Agent (60 s gecacht) |
| `tools/call` | Weiterleitung an den Agent; dessen JSON wird als MCP-Tool-Result zurückgegeben |
| `notifications/*` | 202 quittiert, ohne Body |

Beispiel:

```bash
curl -X POST "http://<domino-host>/mcp/servlet" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"query_view","arguments":{"dbkey":"<dbkey>","viewname":"(All)","count":25}}}'
```

## Konfiguration

Keine. Die Servlet leitet die Agent-URL aus dem einkommenden Request ab:

```
{Scheme}://{Host}[:Port]/mcp_tools.nsf/McpGateway?open
```

Agent-Timeout: 30 s. Fehler des Agents (`status: "error"`) werden als
MCP-Result mit `isError: true` weitergereicht.

## Build

```bash
./build.sh            # Java 8, mvn clean + package (Tests werden übersprungen)
mvn clean package     # alternativ, inkl. Tests
```

Ergebnis:

| Artifact | Deployment |
|---|---|
| `target/domino-mcp-servlet.war` | nach `<domino>/data/java/` kopieren |
| `target/domino-mcp-servlet.jar` | Alternative: JAR **und** `src/main/webapp/WEB-INF/web.xml` in den Domino-Java-Classpath |

## Deployment auf HCL Domino

1. WAR (bzw. JAR + `web.xml`) nach `<domino>/data/java/` kopieren — nur **eine** Servlet-Definition je Server (WAR **oder** JAR)
2. `notes.ini`:
   ```ini
   HTTPJavaEnabled=1
   HTTPClassPath=<domino>/data/java
   ```
3. HTTP Task neu starten: `tell http restart`
4. Endpoint testen: `POST /mcp/servlet` mit `initialize` (s. o.)

## Versionen & Kompatibilität

| Komponente | Version |
|---|---|
| Domino MCP Servlet (dieses Repo) | 2.0.0 |
| Domino MCP Toolkit (OpenNTF) | siehe Release |

Schnittstellenvertrag (Request-/Response-Formate, Fehlercodes):
`AGENT_CONTRACT.md` im OpenNTF-Release **Domino MCP Toolkit**.

## Sicherheit

Die Servlet führt keine eigene Authentifizierung oder Autorisierung
durch — der Zugriffsschutz läuft vollständig über HCL Domino:
ACL der Datenbank `mcp_tools.nsf`, Zugriffsrechte des Web-Agenten
`McpGateway` und die Domino-Berechtigungen der anfragenden Nutzer.
Welche Daten abgerufen werden können, bestimmen die Tools, die in der
Notes-Komponente konfiguriert sind.

**Empfehlung:** Den Endpoint nicht ungesichert ans öffentliche Internet
geben — vorzugsweise hinter einem authentifizierenden Reverse-Proxy
betreiben und die ACL von `mcp_tools.nsf` restriktiv halten.

## Lizenz

Apache License 2.0 — siehe [LICENSE](LICENSE).

---

Domino, HCL und Lotus sind Marken von HCL Software, LLC.
Dieses Projekt ist nicht von HCL gesponsert, genehmigt oder unterstützt.
