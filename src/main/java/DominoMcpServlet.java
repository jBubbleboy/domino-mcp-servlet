import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP-Server-Servlet (JSON-RPC 2.0 über HTTP) als Brücke zwischen LLM und
 * Notes-Datenbank — Komponente des <b>Domino MCP Toolkit</b>.
 *
 * <p><b>Release:</b> Dieses Repository (GitHub: <code>domino-mcp-servlet</code>)
 * enthält nur die Servlet. Die Notes-Komponente — Web-Agent
 * <code>McpGateway</code> und Datenbank <code>mcp_tools.nsf</code> — wird als
 * OpenNTF-Release <b>„Domino MCP Toolkit"</b> ausgeliefert und muss auf dem
 * selben Domino-Server installiert sein.</p>
 *
 * <p><b>Paket:</b> bewusst im Default-Package (wie alle Projekt-Klassen),
 * damit die Domino-web.xml die Servlet mit dem einfachen Namen
 * {@code DominoMcpServlet} registrieren kann.</p>
 *
 * <p><b>Konfiguration:</b> bewusst <b>keine externe Konfiguration</b> (keine
 * INI-Datei; nur die optionalen Init-Parameter {@code mcpMaxPayloadBytes}
 * und {@code mcpAgentTimeoutMs} in der web.xml). Die Servlet spricht immer
 * den Agent {@code McpGateway} in der Datenbank {@code mcp_tools.nsf}
 * direkt im Web-Root an — auf dem <b>dieselben Server</b>, auf dem der
 * MCP-Request eingeht (Host/Scheme/Port werden aus dem einkommenden
 * Request abgeleitet). Body-Guardrail und Agent-Timeout können zur
 * Laufzeit von der vConfig des Agents per op=env übersteuert werden
 * (Präzedenz vConfig &gt; web.xml &gt; eingebauter Default, s.
 * refreshEnvConfig). Damit läuft die Servlet ohne jede Einrichtung von
 * außen korrekt:</p>
 * <pre>
 * Agent-URL = {Scheme des Requests}://{Host des Requests}[:Port]/mcp_tools.nsf/McpGateway?open
 * Aufruf:    HTTP-POST mit JSON-Body, z. B. {{"op":"call","tool":"@View","params":{{...}}}}
 * </pre>
 *
 * <p>Architektur:</p>
 * <pre>
 * [LLM / MCP-Client]
 *       |  HTTP POST (JSON-RPC 2.0)
 *       v
 * [DominoMcpServlet]  (keine externe Konfiguration)
 *       |  HTTP POST (JSON-Body, ?open-Trigger)
 *       v
 * [Notes-DB]  LotusScript-Web-Agent, Print-Ausgabe = JSON.
 *             Die Liste der verfügbaren MCP-Tools und deren Implementierung
 *             wird in der Notes-DB von den Notes-Usern konfiguriert.
 * </pre>
 *
 * <p><b>Dual-Era:</b> Die Servlet spricht beide MCP-Ären am selben Endpoint:</p>
 * <ul>
 *   <li><b>Legacy</b> ({@code 2025-06-18}): {@code initialize}-Handshake, Requests
 *       ohne per-request {@code _meta} — Verhalten unverändert für Alt-Clients.</li>
 *   <li><b>Modern</b> ({@code 2026-07-28}): stateless, kein Handshake — jeder Request
 *       trägt Version und Client-Capabilities in {@code params._meta}
 *       ({@code io.modelcontextprotocol/protocolVersion} +
 *       {@code io.modelcontextprotocol/clientCapabilities}, beide Pflicht).
 *       Zusätzlich sind die HTTP-Header {@code MCP-Protocol-Version} (alle
 *       Requests), {@code Mcp-Method} (alle Requests) und {@code Mcp-Name}
 *       ({@code tools/call}, {@code resources/read}, {@code prompts/get})
 *       verpflichtend und werden gegen den Body validiert (Mismatch → HTTP 400 +
 *       JSON-RPC-Error {@code -32020} HeaderMismatch; {@code Mcp-Name} darf im
 *       Base64-Sentinel-Format {@code =?base64?...?=} kodiert sein).
 *       Modern-Results tragen {@code resultType:"complete"} und
 *       {@code _meta.io.modelcontextprotocol/serverInfo}; List-/Read-/discover-Results
 *       zusätzlich {@code ttlMs} und {@code cacheScope}. Unbekannte Version →
 *       400 + {@code -32022} (mit Liste der unterstützten Versionen); unbekannte
 *       Methode → 404 + {@code -32601}.</li>
 * </ul>
 *
 * <p>Ära-Erkennung pro Request: {@code params._meta} enthält
 * {@code io.modelcontextprotocol/protocolVersion} → Modern-Pfad; sonst
 * (u.&nbsp;a. {@code initialize}) → Legacy-Pfad.</p>
 *
 * <p><b>Security:</b> Der {@code Origin}-Header wird bei jedem POST geprüft
 * (DNS-Rebinding-Schutz): vorhanden und fremd (anderer Host/Port) → HTTP 403.
 * Fehlender {@code Origin} (typisch für Nicht-Browser-Clients) wird
 * durchgelassen.</p>
 *
 * <p><b>Per-User-Domino-Identität (v2.2.0):</b> Die Servlet führt keine eigene
 * Authentifizierung durch; der Domino-HTTP-Task authentifiziert den
 * einkommenden Request (z. B. Basic-Authentication, s.
 * {@code docs/per-user-authentication.md}). Ist der Request authentifiziert
 * ({@code getRemoteUser()} gesetzt), leitet die Servlet den
 * {@code Authorization}-Header über {@link #authForAgentCall} an den
 * internen Agent-Call weiter — so läuft der Web-Agent mit
 * {@code Session.UserName} des MCP-Users und kann dessen ACLs durchsetzen.
 * Unauthentifizierte Requests erzeugen einen anonymen Agent-Call; wird dieser
 * vom Agent mit HTTP 401/403 beantwortet (z. B. ACL von {@code mcp_tools.nsf}),
 * meldet die Servlet den JSON-RPC-Error {@code MCP_1004} ({@code -32003}).</p>
 *
 * <p><b>Resource-Links (v2.3.0):</b> Ressourcen, die in
 * {@code resources/list} ausgeblendet sind (Agent-Feld {@code hidden}),
 * bleiben über die Hub-Resource erreichbar: Die Hub-Antwort trägt
 * {@code resource_link}-Content-Items (MCP 2025-06-18 {@code ResourceLink}),
 * deren URIs die Servlet — nur für die Dauer der Cache-TTL — für
 * {@code resources/read} freischaltet. Beliebige nicht
 * gelistete und nicht referenzierte URIs bleiben mit {@code -32602}
 * abgewiesen.</p>
 *
 * <p><b>Resource-Link-Filter (v2.4.0):</b> Die {@code resource_link}-Items
 * selbst werden aus der Client-Antwort ({@code contents}) <b>vor der
 * Weiterreichung entfernt</b>; der Whitelist-Unlock (v2.3.0) erfolgt
 * davor und bleibt unverändert. Begründung: MCP-Clients mit strenger
 * Schema-Prüfung der {@code resources/read}-Antwort (z. B. opencode)
 * lehnen die gesamte Antwort ab, wenn {@code resource_link}-Items
 * enthalten sind (Zod: {@code invalid_union} auf {@code contents}).
 * Der Client erhält somit ein reines Text-Item (Markdown-Index), die
 * referenzierten URIs sind wie bisher für die Cache-TTL lesbar.</p>
 *
 * <p><b>IPv6-Hosts (v2.4.1):</b> Bei Zugriffen über eine rohe IPv6-Adresse
 * (Host-Header ohne Brackets) wurde die Agent-URL ohne {@code [ ]} um den
 * Host gebildet (ungültige URL) → {@code -32001}/{@code MCP_1003} „Invalid
 * agent URL". Der Host wird jetzt bei der Agent-URL-Komposition
 * IPv6-konform gebracktet ({@link #hostForUrl(String)}).</p>
 *
 * <p>Unterstützte MCP-Methoden:</p>
 * <ul>
 *   <li>{@code initialize} — Legacy-Protokoll-Handshake (serverInfo, capabilities)</li>
 *   <li>{@code server/discover} — (Modern) unterstützt Versionen, Capabilities und
 *       Server-Identität in einem Request (MCP 2026-07-28: server-MUST)</li>
 *   <li>{@code tools/list} — Tool-Definitionen, dynamisch aus der Notes-DB
 *       (geliefert vom Agent, in der Servlet mit TTL gecacht)</li>
 *   <li>{@code tools/call} — Tool-Name + Argumente werden an den Agent
 *       weitergeleitet; dessen JSON-Antwort wird als MCP-Tool-Result zurückgegeben.
 *       Toleranz: zusätzliche Felder auf der params-Ebene (neben
 *       {@code name}/{@code arguments}, z.&nbsp;B. ein dort abgelegter
 *       {@code cursor}) werden mitübergeben, damit ein Folge-Call nicht
 *       versehentlich neu gestartet wird; Felder in {@code arguments}
 *       haben Vorrang.</li>
 *   <li>{@code resources/list} — Ressourcen-Liste, dynamisch aus der Notes-DB
 *       (geliefert vom Agent, in der Servlet mit TTL gecacht)</li>
 *   <li>{@code resources/read} — Resource-URI wird an den Agent weitergeleitet;
 *       dessen JSON-Antwort ({@code contents}) wird als MCP-Result zurückgegeben.
 *       Die Whitelist gilt zusätzlich für URIs, die in der Cache-TTL
 *       ({@code TOOLS_CACHE_TTL_MS}, Default 60 s) als
 *       {@code resource_link}-Content-Item in einer
 *       {@code resources/read}-Antwort referenziert wurden
 *       (v2.3.0, Hub-Ressourcen mit ausgeblendeten Ressourcen). Die
       {@code resource_link}-Items selbst werden dem Client nicht
       mitgeschickt (v2.4.0, Filter vor der Weiterreichung).</li>
 *   <li>{@code prompts/list} — Prompt-Liste, dynamisch aus der Notes-DB
 *       (geliefert vom Agent, in der Servlet mit TTL gecacht)</li>
 *   <li>{@code prompts/get} — Prompt-Name + Argumente werden an den Agent
 *       weitergeleitet; dessen JSON-Antwort ({@code messages}) wird als
 *       MCP-Result zurückgegeben.</li>
 *   <li>{@code notifications/*} — werden mit 202 quittiert (keine Antwort)</li>
 * </ul>
 *
 * <p>Die Servlet hält KEINE Tools selbst vor und verwendet KEINE
 * {@code lotus.domino.*}-API: Sie ist ein reines Gateway. Fehler des Agents
 * ({@code {"status":"error", ...}}) werden als MCP-Result mit
 * {@code isError:true} weitergereicht; ein nicht erreichbarer Agent wird als
 * JSON-RPC-Error MCP_1003 gemeldet.</p>
 *
 * <p><b>Logging:</b> Es wird <b>nichts</b> geloggt (kein SLF4J, keine
 * Konsole, keine Datei) — die einzige Ausgabe der Servlet ist die
 * HTTP-Antwort an den aufrufenden Client.</p>
 */
public class DominoMcpServlet extends HttpServlet {

    private static final long serialVersionUID = 2L;

    /** Feste Notes-Datenbank und Agent-Name (im Web-Root der Servlet). */
    static final String AGENT_DATABASE = "mcp_tools.nsf";
    static final String AGENT_NAME = "McpGateway";

    /**
     * Connect/Read-Timeout je Agent-Call in Millisekunden (eingebauter
     * Default; übersteuerbar über den Init-Parameter
     * {@code mcpAgentTimeoutMs} und — mit höherer Präzedenz — über das
     * vConfig-Feld AGENT_TIMEOUT_MS per op=env, s. refreshEnvConfig).
     * Grösser bei langsamen Agent-Aufrufen, z. B. get_design-Erst-Call
     * (DXL-Export größerer Masken).
     */
    static final int AGENT_TIMEOUT_MS_DEFAULT = 30_000;

    /**
     * Maximale LLM-Body-Größe in Bytes (Guardrail, init-Param
     * {@code mcpMaxPayloadBytes}; übersteuerbar mit höherer Präzedenz
     * über das vConfig-Feld MAX_PAYLOAD_BYTES per op=env, s.
     * refreshEnvConfig). Default: das Server-HTTP-Limit
     * ({@code Content-Length ≥ 10 240 000} → HTTP 500, gemessen) abzgl. Puffer.
     * Wird das Server-Limit erhöht, hier (und Agent {@code FILECALL_MAX_BYTES})
     * entsprechend erhöhen. Messbericht:
     * DomAI_MCP_McpGateway/docs/2026-09-18_http_size_limits.md.
     */
    static final long MAX_PAYLOAD_BYTES_DEFAULT = 10_239_999L;

    /** TTL des Agent-Env-Caches in Millisekunden (konsistent mit den Registry-Caches). */
    static final long ENV_CACHE_TTL_MS = 60_000L;

    /** TTL der Tool-Liste-Caches in Millisekunden (Tests dürfen anpassen). */
    static volatile long TOOLS_CACHE_TTL_MS = 60_000L;

    /** Default des MCP-Server-Namens (serverInfo.name); übersteuerbar über den
     *  Init-Parameter {@code mcpServerName} und - mit höherer Präzedenz - über
     *  das vConfig-Feld SERVER_NAME per op=env, s. refreshEnvConfig. */
    static final String SERVER_NAME_DEFAULT = "Domino";
    static final String SERVER_VERSION = "2.4.1";
    static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    /** Modern-Ära (MCP 2026-07-28, stateless) — unterstützte Modern-Versionen. */
    static final String MODERN_PROTOCOL_VERSION = "2026-07-28";
    static final String[] MODERN_SUPPORTED_VERSIONS = {MODERN_PROTOCOL_VERSION};

    /** Reservierte _meta-Keys der Modern-Ära (MCP 2026-07-28). */
    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    /** cacheScope für Cacheable-Results: Daten hängen vom Domino-User/ACL ab. */
    static final String CACHE_SCOPE = "private";

    // JSON-RPC-Error-Codes
    static final int ERR_PARSE = -32700;
    static final int ERR_INVALID_REQUEST = -32600;
    static final int ERR_METHOD_NOT_FOUND = -32601;
    static final int ERR_INVALID_PARAMS = -32602;
    static final int ERR_AGENT_UNREACHABLE = -32001; // MCP_1003 (grandfathered)
    static final int ERR_AUTH_REQUIRED = -32003; // MCP_1004 (Agent-401/403: keine gültige Domino-Auth)
    static final int ERR_HEADER_MISMATCH = -32020; // MCP 2026-07-28: HeaderMismatch
    static final int ERR_UNSUPPORTED_PROTOCOL_VERSION = -32022; // MCP 2026-07-28

    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ResourceRegistry resourceRegistry = new ResourceRegistry();
    private final PromptRegistry promptRegistry = new PromptRegistry();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Maximale LLM-Body-Größe in Bytes (init-Param, Default s. Konstante;
     *  zur Laufzeit von der vConfig per op=env übersteuerbar, s. refreshEnvConfig). */
    private volatile long maxPayloadBytes = MAX_PAYLOAD_BYTES_DEFAULT;

    /** Liefert die eingestellte Body-Größen-Grenze (Tests dürfen anpassen). */
    long maxPayloadBytes() {
        return maxPayloadBytes;
    }

    /** Connect/Read-Timeout je Agent-Call in ms (init-Param, Default s. Konstante;
     *  zur Laufzeit von der vConfig per op=env übersteuerbar, s. refreshEnvConfig). */
    private volatile int agentTimeoutMs = AGENT_TIMEOUT_MS_DEFAULT;

    /** Liefert den eingestellten Agent-Timeout (Tests dürfen anpassen). */
    int agentTimeoutMs() {
        return agentTimeoutMs;
    }

    /** MCP-Server-Name (serverInfo.name) für die Client-Identität; init-Param
     *  {@code mcpServerName}, Default s. Konstante; zur Laufzeit von der vConfig
     *  per op=env übersteuerbar, s. refreshEnvConfig. */
    private volatile String serverName = SERVER_NAME_DEFAULT;

    /** Liefert den eingestellten Server-Namen (Tests dürfen anpassen). */
    String serverName() {
        return serverName;
    }

    @Override
    public void init() {
        String param = getInitParameter("mcpMaxPayloadBytes");
        if (param != null && !param.trim().isEmpty()) {
            try {
                long value = Long.parseLong(param.trim());
                if (value > 0) {
                    maxPayloadBytes = value;
                } else {
                    maxPayloadBytes = MAX_PAYLOAD_BYTES_DEFAULT;
                }
            } catch (NumberFormatException e) {
                maxPayloadBytes = MAX_PAYLOAD_BYTES_DEFAULT;
            }
        }

        String timeoutParam = getInitParameter("mcpAgentTimeoutMs");
        if (timeoutParam != null && !timeoutParam.trim().isEmpty()) {
            try {
                long value = Long.parseLong(timeoutParam.trim());
                if (value > 0 && value <= Integer.MAX_VALUE) {
                    agentTimeoutMs = (int) value;
                } else {
                    agentTimeoutMs = AGENT_TIMEOUT_MS_DEFAULT;
                }
            } catch (NumberFormatException e) {
                agentTimeoutMs = AGENT_TIMEOUT_MS_DEFAULT;
            }
        }

        String nameParam = getInitParameter("mcpServerName");
        if (nameParam != null && !nameParam.trim().isEmpty()) {
            serverName = nameParam.trim();
        }
    }

    /**
     * Holt die Servlet-Konfiguration (Agent-Timeout + maximale LLM-Body-
     * Größe) aus der vConfig des Agents ({@code op=env}) und wendet sie
     * an, wenn der Agent positive Werte liefert (Präzedenz vConfig &gt;
     * web.xml-Init-Param &gt; eingebauter Default; Werte 1:1 ohne
     * Plausibilitäts-Check, s. AGENT_CONTRACT 3.5). 60-s-Cache (
     * {@link FileCallEnv}); fehlende/ungültige Werte (0) lassen den
     * aktuellen Wert unverändert. Fail-Open: Agent nicht erreichbar oder
     * Env-Antwort ungültig → die Servlet arbeitet still mit den
     * aktuellen Werten weiter (keine Ausgabe, s. Logging-Kapitel).
     */
    private void refreshEnvConfig(NotesAgentClient client, String agentBase) {
        FileCallEnv.Env env;
        try {
            env = FileCallEnv.get(client, agentBase, agentTimeoutMs, ENV_CACHE_TTL_MS);
        } catch (IOException e) {
            return;
        }
        if (env.agentTimeoutMs > 0) {
            agentTimeoutMs = (int) Math.min(env.agentTimeoutMs, Integer.MAX_VALUE);
        }
        if (env.maxPayloadBytes > 0) {
            maxPayloadBytes = env.maxPayloadBytes;
        }
        if (env.serverName != null && !env.serverName.isEmpty()) {
            serverName = env.serverName;
        }
    }

    /** true, wenn die MCP-Methode den Agent anspricht. */
    private static boolean usesAgent(String method) {
        switch (method) {
            case "tools/list":
            case "tools/call":
            case "resources/list":
            case "resources/read":
            case "prompts/list":
            case "prompts/get":
                return true;
            default:
                return false;
        }
    }

    /** true, wenn vor dem Result die vConfig-Server-Konfiguration (op=env)
     *  aufzufrischen ist: alle Agent-Methoden + die Handshake-Methoden
     *  initialize/server-discover — damit serverInfo.name ab dem allerersten
     *  Request den vConfig-Wert SERVER_NAME trägt (s. serverName). Fail-Open. */
    private static boolean needsEnvRefresh(String method) {
        return usesAgent(method)
                || "initialize".equals(method)
                || "server/discover".equals(method);
    }

    // ---------- HTTP ----------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        resp.setContentType("application/json; charset=utf-8");
        PrintWriter w = resp.getWriter();
        w.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + ERR_METHOD_NOT_FOUND
                + ",\"message\":\"Only POST with JSON-RPC 2.0 is supported\"},\"id\":null}");
        w.flush();
    }

    /**
     * DELETE auf den MCP-Endpoint: 405. (Alt-Streamable-HTTP-Clients versuchten
     * hier das Session-Ende; Sessions kennt diese Servlet nicht — MCP 2026-07-28
     * und die Servlet sind stateless.)
     */
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // charset= im Content-Type: setCharacterEncoding(String) ist erst Servlet 3.0,
        // das Domino-JSDK implementiert nur Servlet 2.4 (NoSuchMethodError sonst).
        resp.setContentType("application/json; charset=utf-8");

        // DNS-Rebinding-Schutz: vorhandener, fremder Origin-Header wird abgewiesen.
        if (!originAllowed(req)) {
            sendForbidden(resp);
            return;
        }

        String body;
        try {
            body = readBody(req);
        } catch (BodySizeException e) {
            // Body-Guardrail (s. readBody): zu groß oder Größen-Mismatch
            // (Replay-Zone des Domino-HTTP-Tasks) → sauberer -32602 statt
            // rohem 500-HTML oder verarbeitetem, nicht-vertrauenswürdigen Body.
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params", e.getMessage(), null,
                    HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            sendError(resp, ERR_PARSE, "Parse error", "Invalid JSON in request: " + e.getMessage(), null);
            return;
        }
        if (root == null || !root.isObject()) {
            sendError(resp, ERR_INVALID_REQUEST, "Invalid Request",
                    "Request root must be a JSON object", null);
            return;
        }

        JsonNode id = root.get("id");
        JsonNode methodNode = root.get("method");
        if (methodNode == null || !methodNode.isTextual() || methodNode.asText().isEmpty()) {
            sendError(resp, ERR_INVALID_REQUEST, "Invalid Request",
                    "Field 'method' (non-empty string) is missing", id);
            return;
        }
        String method = methodNode.asText();
        JsonNode params = root.get("params");

        // Benachrichtigungen (z. B. notifications/initialized) erhalten keine Antwort.
        if (method.startsWith("notifications/")) {
            resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            return;
        }

        // Der Agent liegt immer auf dem Server, auf dem der Request eingeht.
        String agentBase = agentBaseUrl(req);

        // Dual-Era-Ära-Erkennung: Modern-Requests (MCP 2026-07-28, stateless) tragen
        // die Protokoll-Version pro Request in params._meta; alles andere (u. a.
        // initialize) ist Legacy und wird wie bisher behandelt.
        JsonNode meta = (params != null && params.isObject()) ? params.get("_meta") : null;
        String requestedVersion = null;
        if (meta != null && meta.isObject()) {
            JsonNode v = meta.get(META_PROTOCOL_VERSION);
            if (v != null && v.isTextual() && !v.asText().trim().isEmpty()) {
                requestedVersion = v.asText().trim();
            }
        }
        final boolean modern = requestedVersion != null;

        // Per-request Agent-Client: Der Authorization-Header wird nur
        // weitergereicht, wenn der Domino-HTTP-Task den Request tatsächlich
        // authentifiziert hat (s. authForAgentCall). Ein Client pro Request
        // => thread-sicher ohne mutablen Zustand.
        final NotesAgentClient reqAgentClient = new NotesAgentClient(authForAgentCall(req));

        // Servlet-Konfiguration aus der vConfig (op=env, 60-s-Cache,
        // Fail-Open): Agent-Methoden + Handshake (initialize/server-discover),
        // damit serverInfo.name ab dem allerersten Request den vConfig-Wert
        // SERVER_NAME trägt.
        if (needsEnvRefresh(method)) {
            refreshEnvConfig(reqAgentClient, agentBase);
        }

        try {
            if (modern) {
                // Modern: Client-Capabilities sind pro Request Pflicht.
                if (meta.get(META_CLIENT_CAPABILITIES) == null) {
                    sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                            "Field _meta.\"io.modelcontextprotocol/clientCapabilities\" is missing",
                            id, HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
                if (!isModernVersion(requestedVersion)) {
                    ObjectNode data = mapper.createObjectNode();
                    ArrayNode supported = data.putArray("supported");
                    for (String v : MODERN_SUPPORTED_VERSIONS) {
                        supported.add(v);
                    }
                    data.put("requested", requestedVersion);
                    sendErrorData(resp, ERR_UNSUPPORTED_PROTOCOL_VERSION,
                            "Unsupported protocol version", data, id,
                            HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
                String headerError = validateModernHeaders(req, method, params, requestedVersion);
                if (headerError != null) {
                    sendError(resp, ERR_HEADER_MISMATCH, "Header mismatch", headerError, id,
                            HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
            }

            switch (method) {
                case "initialize":
                    handleInitialize(resp, id, params);
                    break;
                case "server/discover":
                    handleServerDiscover(resp, id);
                    break;
                case "tools/list":
                    handleToolsList(resp, id, reqAgentClient, agentBase, modern);
                    break;
                case "tools/call":
                    handleToolsCall(resp, id, params, reqAgentClient, agentBase, modern);
                    break;
                case "resources/list":
                    handleResourcesList(resp, id, reqAgentClient, agentBase, modern);
                    break;
                case "resources/read":
                    handleResourcesRead(resp, id, params, reqAgentClient, agentBase, modern);
                    break;
                case "prompts/list":
                    handlePromptsList(resp, id, reqAgentClient, agentBase, modern);
                    break;
                case "prompts/get":
                    handlePromptsGet(resp, id, params, reqAgentClient, agentBase, modern);
                    break;
                default:
                    // Modern: unbekannte Methode → 404 + -32601 (Spec 2026-07-28);
                    // Legacy bleibt 200 + -32601 (Alt-Clients lesen nur den Body).
                    sendError(resp, ERR_METHOD_NOT_FOUND, "Method not found",
                            "Method not supported: " + method, id,
                            modern ? HttpServletResponse.SC_NOT_FOUND
                                   : HttpServletResponse.SC_OK);
            }
        } catch (IOException e) {
            // Sicherheitshalber: ungeplante I/O-Fehler -> JSON-RPC-Error statt leerer Antwort
            sendError(resp, ERR_AGENT_UNREACHABLE, "MCP_1003: internal I/O error",
                    String.valueOf(e.getMessage()), id);
        }
    }

    /**
     * Agent-Base-URL: immer der Server des einkommenden Requests
     * (Scheme + Host [+ Nicht-Standard-Port]), dahinter die festen
     * {@code /mcp_tools.nsf/McpGateway} (ohne Query-String). IPv6-Hosts
     * werden in {@code [ ]} gesetzt (die URL-Syntax verlangt das; die
     * Servlet-API liefert Hosts ohne Brackets).
     */
    static String agentBaseUrl(HttpServletRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.getScheme()).append("://").append(hostForUrl(req.getServerName()));
        int port = req.getServerPort();
        boolean defaultPort = ("http".equals(req.getScheme()) && port == 80)
                || ("https".equals(req.getScheme()) && port == 443);
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        sb.append('/').append(AGENT_DATABASE).append('/').append(AGENT_NAME);
        return sb.toString();
    }

    /**
     * Host-Anteil einer URL: IPv6-Literal wird immer in {@code [ ]} gesetzt
     * (URL-Syntax verlangt das; die Servlet-API liefert Hosts ohne Brackets,
     * ein bereits gebracketer Host wird erst entbracktet, damit keine
     * doppelten Brackets entstehen). Hosts ohne {@code :} (IPv4/DNS)
     * bleiben unveraendert.
     */
    static String hostForUrl(String host) {
        if (host.indexOf(':') < 0) {
            return host;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return "[" + host + "]";
    }

    /**
     * Liefert den Authorization-Header für den internen Agent-Call — oder
     * null, wenn der Request nicht authentifiziert ist.
     *
     * <p><b>Gate:</b> Weitergereicht wird nur, wenn der Domino-HTTP-Task den
     * einkommenden Request tatsächlich authentifiziert hat (dann ist
     * {@code getRemoteUser()} gesetzt). Das verhindert, dass ein anonymer
     * externer Client fremde Credentials per Header gegen den internen
     * Agent-Call schiebt (Anti-Spoofing): Solange der HTTP-Task den User
     * nicht validiert hat, bleibt der Agent-Call anonym — und scheitert
     * (falls gewünscht) an der ACL von {@code mcp_tools.nsf}.</p>
     */
    static String authForAgentCall(HttpServletRequest req) {
        if (req.getRemoteUser() == null) {
            return null;
        }
        String header = req.getHeader("Authorization");
        return (header != null && !header.trim().isEmpty()) ? header.trim() : null;
    }

    // ---------- Dual-Era: Modern-Pfad (MCP 2026-07-28) ----------

    /** true, wenn die Version zu den unterstützten Modern-Versionen gehört. */
    static boolean isModernVersion(String version) {
        for (String v : MODERN_SUPPORTED_VERSIONS) {
            if (v.equals(version)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validiert die Standard-Header eines Modern-Requests gegen den Body
     * (MCP 2026-07-28, Streamable HTTP): {@code MCP-Protocol-Version} (alle
     * Requests), {@code Mcp-Method} (alle Requests), {@code Mcp-Name}
     * ({@code tools/call}, {@code resources/read}, {@code prompts/get}, ggf. im
     * Base64-Sentinel-Format). Liefert null bei Erfolg, sonst eine
     * Fehlermeldung (→ HTTP 400 + JSON-RPC-Error -32020 HeaderMismatch).
     */
    static String validateModernHeaders(HttpServletRequest req, String method,
                                        JsonNode params, String version) {
        String pv = req.getHeader("MCP-Protocol-Version");
        if (!isHeaderValue(pv)) {
            return "Header MCP-Protocol-Version is missing or invalid";
        }
        if (!version.equals(pv)) {
            return "MCP-Protocol-Version header \"" + pv + "\" does not match the "
                    + "body version \"" + version + "\"";
        }
        String mh = req.getHeader("Mcp-Method");
        if (!isHeaderValue(mh)) {
            return "Header Mcp-Method is missing or invalid";
        }
        if (!method.equals(mh)) {
            return "Mcp-Method header \"" + mh + "\" does not match the body method \""
                    + method + "\"";
        }
        boolean nameFromUri = "resources/read".equals(method);
        boolean needsName = "tools/call".equals(method) || "prompts/get".equals(method) || nameFromUri;
        if (needsName) {
            JsonNode nameNode = nameFromUri ? params.get("uri") : params.get("name");
            if (nameNode == null || !nameNode.isTextual()) {
                return "Field 'params." + (nameFromUri ? "uri" : "name")
                        + "' is missing (Mcp-Name header cannot be compared)";
            }
            String nh = req.getHeader("Mcp-Name");
            if (!isHeaderValue(nh)) {
                return "Header Mcp-Name is missing or invalid";
            }
            String decoded = decodeBase64Sentinel(nh);
            if (decoded == null) {
                return "Mcp-Name header invalid (Base64 sentinel not decodable)";
            }
            if (!decoded.equals(nameNode.asText())) {
                return "Mcp-Name header \"" + decoded + "\" does not match the body value \""
                        + nameNode.asText() + "\"";
            }
        }
        return null;
    }

    /**
     * Dekodiert einen Base64-Sentinel-Headerwert ({@code =?base64?...?=},
     * UTF-8); plain-ASCII-Werte werden unverändert zurückgegeben. Liefert
     * null, wenn der Sentinel nicht dekodierbar ist.
     */
    static String decodeBase64Sentinel(String value) {
        if (value != null && value.length() >= 11
                && value.startsWith("=?base64?") && value.endsWith("?=")) {
            String encoded = value.substring(9, value.length() - 2);
            try {
                return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return value;
    }

    /**
     * true, wenn der Wert ein zulässiges HTTP-Header-Value ist (RFC 9110:
     * VCHAR 0x21–0x7E, SP, HTAB). Erkennt unzulässige Zeichen (u. a. CR/LF
     * und Non-ASCII) und verhindert so Header-Injection. Erlaubt damit auch
     * Werte mit {@code /}, {@code (}, {@code :} wie {@code tools/call} oder
     * Resource-URIs.
     */
    static boolean isHeaderValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\t' && (c < 0x21 || c > 0x7E)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Same-Origin-Prüfung des Origin-Headers (DNS-Rebinding-Schutz):
     * fehlender Origin → true (Nicht-Browser-Clients senden keinen Origin);
     * vorhandener, fremder (anderes Scheme/Host/Port) oder ungültiger
     * Origin → false (→ HTTP 403).
     */
    static boolean originAllowed(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        origin = origin.trim();
        if (origin.isEmpty() || origin.equalsIgnoreCase("null")) {
            return false;
        }
        URL o;
        try {
            o = new URL(origin);
        } catch (MalformedURLException e) {
            return false;
        }
        String scheme = o.getProtocol();
        String host = o.getHost();
        if (scheme == null || host == null
                || !scheme.equalsIgnoreCase(req.getScheme())
                || !host.equalsIgnoreCase(req.getServerName())) {
            return false;
        }
        int expectedPort;
        int port = o.getPort();
        if (port == -1) {
            expectedPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        } else {
            expectedPort = port;
        }
        return expectedPort == req.getServerPort();
    }

    // ---------- Method-Handler ----------

    /**
     * (Modern, MCP 2026-07-28: server-MUST) Werbet die unterstützten
     * Protokoll-Versionen, Capabilities und die Server-Identität in einem
     * Request an.
     */
    private void handleServerDiscover(HttpServletResponse resp, JsonNode id) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode supported = result.putArray("supportedVersions");
        for (String v : MODERN_SUPPORTED_VERSIONS) {
            supported.add(v);
        }
        ObjectNode capabilities = result.putObject("capabilities");
        ObjectNode toolsCap = capabilities.putObject("tools");
        toolsCap.put("listChanged", false);
        ObjectNode resourcesCap = capabilities.putObject("resources");
        resourcesCap.put("listChanged", false);
        resourcesCap.put("subscribe", false);
        ObjectNode promptsCap = capabilities.putObject("prompts");
        promptsCap.put("listChanged", false);
        sendModernResult(resp, id, result, true);
    }

    private void handleInitialize(HttpServletResponse resp, JsonNode id, JsonNode params) throws IOException {
        String protocolVersion = DEFAULT_PROTOCOL_VERSION;
        if (params != null && params.isObject()) {
            JsonNode pv = params.get("protocolVersion");
            if (pv != null && pv.isTextual() && !pv.asText().trim().isEmpty()) {
                protocolVersion = pv.asText().trim();
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", protocolVersion);
        ObjectNode capabilities = result.putObject("capabilities");
        ObjectNode toolsCap = capabilities.putObject("tools");
        toolsCap.put("listChanged", false);
        ObjectNode resourcesCap = capabilities.putObject("resources");
        resourcesCap.put("listChanged", false);
        resourcesCap.put("subscribe", false);
        ObjectNode promptsCap = capabilities.putObject("prompts");
        promptsCap.put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", serverName);
        serverInfo.put("version", SERVER_VERSION);
        sendResult(resp, id, result);
    }

    private void handleToolsList(HttpServletResponse resp, JsonNode id,
                                 NotesAgentClient client, String agentBase,
                                 boolean modern) throws IOException {
        List<ToolRegistry.ToolDefinition> tools;
        try {
            tools = toolRegistry.getTools(client, agentBase, agentTimeoutMs, TOOLS_CACHE_TTL_MS);
        } catch (IOException e) {
            // Agent nicht erreichbar: alten Cache liefern, falls vorhanden.
            tools = toolRegistry.getCached();
            if (tools.isEmpty()) {
                sendAgentError(resp, id, e);
                return;
            }
        }
        ObjectNode result = mapper.createObjectNode();
        ArrayNode arr = mapper.createArrayNode();
        result.set("tools", arr);
        // Vollstaendiger Agent-Tool-Eintrag (name, description, inputSchema und
        // optionale Felder wie outputSchema/annotations) wird 1:1 weitergegeben.
        // deepCopy pro Request: Ein Jackson-Node hat nur einen Parent, der Cache
        // haelt die Originalinstanz.
        for (ToolRegistry.ToolDefinition t : tools) {
            arr.add(t.entry.deepCopy());
        }
        if (modern) {
            sendModernResult(resp, id, result, true);
        } else {
            sendResult(resp, id, result);
        }
    }

    private void handleToolsCall(HttpServletResponse resp, JsonNode id, JsonNode params,
                                 NotesAgentClient client, String agentBase,
                                 boolean modern) throws IOException {
        if (params == null || !params.isObject()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "tools/call requires 'params' as an object with 'name' and optional 'arguments'", id);
            return;
        }
        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().trim().isEmpty()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "Field 'params.name' (tool name) is missing", id);
            return;
        }
        final String toolName = nameNode.asText().trim();
        final Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        JsonNode argsNode = params.get("arguments");
        if (argsNode != null && argsNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = argsNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                arguments.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class));
            }
        }
        // Toleranz: zusätzliche Felder auf der params-Ebene (neben 'name' und
        // 'arguments') — z. B. ein 'cursor', den ein Client nicht in arguments
        // legt — werden ebenfalls an den Agent weitergeleitet, damit ein
        // Folge-Call nicht versehentlich einen neuen CM-Doc erzeugt.
        // Felder in 'arguments' haben Vorrang.
        for (Iterator<Map.Entry<String, JsonNode>> it = params.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (!"name".equals(key) && !"arguments".equals(key)
                    && !arguments.containsKey(key)) {
                arguments.put(key, mapper.convertValue(e.getValue(), Object.class));
            }
        }

        // Tool gegen die aus der Notes-DB geladene Liste validieren.
        List<ToolRegistry.ToolDefinition> known;
        try {
            known = toolRegistry.getTools(client, agentBase, agentTimeoutMs, TOOLS_CACHE_TTL_MS);
        } catch (IOException e) {
            sendAgentError(resp, id, e);
            return;
        }
        boolean knownTool = false;
        for (ToolRegistry.ToolDefinition t : known) {
            if (t.name.equals(toolName)) {
                knownTool = true;
                break;
            }
        }
        if (!knownTool) {
            sendError(resp, ERR_INVALID_PARAMS, "Unknown tool",
                    "Tool is not defined in the Notes DB: " + toolName, id);
            return;
        }

        // Tool-Call an den Agent weiterleiten.
        String agentJson;
        try {
            agentJson = client.callTool(agentBase, agentTimeoutMs, toolName, arguments);
        } catch (IOException e) {
            sendAgentError(resp, id, e);
            return;
        }

        // Agent-Antwort als MCP-Tool-Result verpacken; Inhalt bleibt der Agent-JSON-Text.
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        result.set("content", content);
        ObjectNode textItem = content.addObject();
        textItem.put("type", "text");
        boolean isError;
        try {
            JsonNode parsed = mapper.readTree(agentJson);
            if (parsed == null) {
                textItem.put("text", "Agent returned an empty response");
                isError = true;
            } else {
                textItem.put("text", mapper.writeValueAsString(parsed));
                // structuredContent (MCP 2025-06-18): dasselbe Agent-JSON als
                // echtes JSON-Objekt neben dem Text-Content; content[0].text
                // bleibt Pflicht wegen der Abwärtskompatibilität.
                result.set("structuredContent", parsed);
                isError = agentSignalsError(parsed);
            }
        } catch (IOException e) {
            // Agent hat (ungewollt) kein JSON geprintet.
            textItem.put("text", "Agent did not return valid JSON: " + truncate(agentJson));
            isError = true;
        }
        result.put("isError", isError);
        if (modern) {
            sendModernResult(resp, id, result, false);
        } else {
            sendResult(resp, id, result);
        }
    }

    // ---------- Ressourcen-Handler ----------

    private void handleResourcesList(HttpServletResponse resp, JsonNode id,
                                     NotesAgentClient client, String agentBase,
                                     boolean modern) throws IOException {
        ArrayNode resources;
        try {
            resources = resourceRegistry.getResources(client, agentBase,
                    agentTimeoutMs, TOOLS_CACHE_TTL_MS);
        } catch (IOException e) {
            // Agent nicht erreichbar: alten Cache liefern, falls vorhanden.
            resources = resourceRegistry.getCached();
            if (resources == null) {
                sendAgentError(resp, id, e);
                return;
            }
        }
        ObjectNode result = mapper.createObjectNode();
        // deepCopy: Ein Jackson-Node hat nur einen Parent, der Cache hält die
        // Originalinstanz.
        result.set("resources", resources.deepCopy());
        if (modern) {
            sendModernResult(resp, id, result, true);
        } else {
            sendResult(resp, id, result);
        }
    }

    private void handleResourcesRead(HttpServletResponse resp, JsonNode id, JsonNode params,
                                      NotesAgentClient client, String agentBase,
                                      boolean modern) throws IOException {
        if (params == null || !params.isObject()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "resources/read requires 'params' as an object with 'uri'", id);
            return;
        }
        JsonNode uriNode = params.get("uri");
        if (uriNode == null || !uriNode.isTextual() || uriNode.asText().trim().isEmpty()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "Field 'params.uri' (resource URI) is missing", id);
            return;
        }
        final String uri = uriNode.asText().trim();

        // Resource gegen die aus der Notes-DB geladene Liste validieren.
        try {
            resourceRegistry.getResources(client, agentBase,
                    agentTimeoutMs, TOOLS_CACHE_TTL_MS);
        } catch (IOException e) {
            sendAgentError(resp, id, e);
            return;
        }
        if (!resourceRegistry.hasUri(uri)) {
            sendError(resp, ERR_INVALID_PARAMS, "Unknown resource",
                    "Resource is not defined in the Notes DB: " + uri, id);
            return;
        }

        // Ressourcen-Read an den Agent weiterleiten.
        String agentJson;
        try {
            agentJson = client.readResource(agentBase, agentTimeoutMs, uri);
        } catch (IOException e) {
            sendAgentError(resp, id, e);
            return;
        }
        JsonNode parsed = parseAgentJson(resp, id, agentJson);
        if (parsed == null) {
            return;
        }
        if (agentSignalsError(parsed)) {
            sendAgentLogicalError(resp, id, parsed);
            return;
        }
        // v2.3.0: resource_link-Referenzen dieser Antwort entriegeln die
        // verlinkten URIs für weitere resources/read (Whitelist-Erweiterung).
        JsonNode contents = parsed.get("contents");
        resourceRegistry.addLinkedUris(contents, TOOLS_CACHE_TTL_MS);

        ObjectNode result = mapper.createObjectNode();
        // v2.4.0: resource_link-Items werden dem Client nicht mitgeschickt
        // (strikte MCP-Clients lehnen solche Antworten ab); der Unlock
        // erfolgt oben, vor dem Filter.
        result.set("contents", filterResourceLinks(contents));
        if (modern) {
            sendModernResult(resp, id, result, true);
        } else {
            sendResult(resp, id, result);
        }
    }

    /**
     * v2.4.0: Entfernt {@code resource_link}-Content-Items aus einer
     * {@code resources/read}-Antwort, bevor sie an den Client geht.
     * Alle anderen Items (Text/Blob, bzw. Items ohne {@code type}-Feld)
     * werden unverändert durchgereicht. Kein Array => leeres Array
     * (verhält sich wie die frühere Fallback-Logik).
     */
    ArrayNode filterResourceLinks(JsonNode contents) {
        ArrayNode out = mapper.createArrayNode();
        if (contents != null && contents.isArray()) {
            for (JsonNode item : contents) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                JsonNode type = item.get("type");
                if (type != null && "resource_link".equals(type.asText())) {
                    continue;
                }
                out.add(item.deepCopy());
            }
        }
        return out;
    }

    // ---------- Prompt-Handler ----------

    private void handlePromptsList(HttpServletResponse resp, JsonNode id,
                                   NotesAgentClient client, String agentBase,
                                   boolean modern) throws IOException {
        ArrayNode prompts;
        try {
            prompts = promptRegistry.getPrompts(client, agentBase,
                    agentTimeoutMs, TOOLS_CACHE_TTL_MS);
        } catch (IOException e) {
            // Agent nicht erreichbar: alten Cache liefern, falls vorhanden.
            prompts = promptRegistry.getCached();
            if (prompts == null) {
                sendAgentError(resp, id, e);
                return;
            }
        }
        ObjectNode result = mapper.createObjectNode();
        // deepCopy: Ein Jackson-Node hat nur einen Parent, der Cache hält die
        // Originalinstanz.
        result.set("prompts", prompts.deepCopy());
        if (modern) {
            sendModernResult(resp, id, result, true);
        } else {
            sendResult(resp, id, result);
        }
    }

    private void handlePromptsGet(HttpServletResponse resp, JsonNode id, JsonNode params,
                                  NotesAgentClient client, String agentBase,
                                  boolean modern) throws IOException {
        if (params == null || !params.isObject()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "prompts/get requires 'params' as an object with 'name'", id);
            return;
        }
        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().trim().isEmpty()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "Field 'params.name' (prompt name) is missing", id);
            return;
        }
        final String name = nameNode.asText().trim();
        final Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        JsonNode argsNode = params.get("arguments");
        if (argsNode != null && argsNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = argsNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                arguments.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class));
            }
        }

        // Prompt gegen die aus der Notes-DB geladene Liste validieren.
        try {
            promptRegistry.getPrompts(client, agentBase,
                    agentTimeoutMs, TOOLS_CACHE_TTL_MS);
        } catch (IOException e) {
            sendAgentError(resp, id, e);
            return;
        }
        if (!promptRegistry.hasName(name)) {
            sendError(resp, ERR_INVALID_PARAMS, "Unknown prompt",
                    "Prompt is not defined in the Notes DB: " + name, id);
            return;
        }

        // Prompt-Get an den Agent weiterleiten.
        String agentJson;
        try {
            agentJson = client.getPrompt(agentBase, agentTimeoutMs, name, arguments);
        } catch (IOException e) {
            sendAgentError(resp, id, e);
            return;
        }
        JsonNode parsed = parseAgentJson(resp, id, agentJson);
        if (parsed == null) {
            return;
        }
        if (agentSignalsError(parsed)) {
            sendAgentLogicalError(resp, id, parsed);
            return;
        }
        ObjectNode result = mapper.createObjectNode();
        JsonNode description = parsed.get("description");
        if (description != null && description.isTextual()) {
            result.put("description", description.asText());
        }
        JsonNode messages = parsed.get("messages");
        result.set("messages", (messages != null && messages.isArray())
                ? messages : mapper.createArrayNode());
        if (modern) {
            sendModernResult(resp, id, result, false);
        } else {
            sendResult(resp, id, result);
        }
    }

    /**
     * Parst die Agent-Antwort; liefert null (und sendet einen JSON-RPC-Fehler),
     * wenn kein gültiges JSON geliefert wurde.
     */
    private JsonNode parseAgentJson(HttpServletResponse resp, JsonNode id, String agentJson)
            throws IOException {
        try {
            return mapper.readTree(agentJson);
        } catch (IOException e) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid result",
                    "Agent did not return valid JSON: " + truncate(agentJson), id);
            return null;
        }
    }

    /**
     * Logischer Agent-Fehler bei resources/read bzw. prompts/get als
     * JSON-RPC-Error (-32602) mit der Agent-Meldung ausgeben.
     */
    private void sendAgentLogicalError(HttpServletResponse resp, JsonNode id, JsonNode agentJson)
            throws IOException {
        String msg = "Agent error";
        if (agentJson != null && agentJson.isObject()) {
            JsonNode m = agentJson.get("message");
            if (m != null && m.isTextual()) {
                msg = m.asText();
            }
        }
        sendError(resp, ERR_INVALID_PARAMS, "Agent error", msg, id);
    }

    /** Liefert true, wenn die Agent-Antwort einen Fehler signalisiert. */
    private static boolean agentSignalsError(JsonNode agentJson) {
        if (agentJson == null || !agentJson.isObject()) {
            return false;
        }
        JsonNode status = agentJson.get("status");
        if (status != null && status.isTextual() && "error".equals(status.asText())) {
            return true;
        }
        JsonNode error = agentJson.get("error");
        return error != null && !error.isNull();
    }

    // ---------- Response-Helfer ----------

    private void sendResult(HttpServletResponse resp, JsonNode id, JsonNode result) throws IOException {
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id != null ? id : NullNode.getInstance());
        out.set("result", result);
        writeJson(resp, out);
    }

    /**
     * Modern-Result verpacken (MCP 2026-07-28): {@code resultType:"complete"}
     * und {@code _meta.io.modelcontextprotocol/serverInfo} in jedem Result;
     * {@code ttlMs}/{@code cacheScope} nur für Cacheable-Results
     * (tools/list, resources/list, prompts/list, resources/read,
     * server/discover).
     */
    private void sendModernResult(HttpServletResponse resp, JsonNode id, ObjectNode result,
                                  boolean cacheable) throws IOException {
        result.put("resultType", "complete");
        ObjectNode meta = result.putObject("_meta");
        ObjectNode serverInfo = meta.putObject(META_SERVER_INFO);
        serverInfo.put("name", serverName);
        serverInfo.put("version", SERVER_VERSION);
        if (cacheable) {
            result.put("ttlMs", TOOLS_CACHE_TTL_MS);
            result.put("cacheScope", CACHE_SCOPE);
        }
        sendResult(resp, id, result);
    }

    private void sendError(HttpServletResponse resp, int code, String message, String detail, JsonNode id)
            throws IOException {
        sendError(resp, code, message, detail, id, HttpServletResponse.SC_OK);
    }

    private void sendError(HttpServletResponse resp, int code, String message, String detail,
                           JsonNode id, int httpStatus) throws IOException {
        resp.setStatus(httpStatus);
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id != null ? id : NullNode.getInstance());
        ObjectNode err = out.putObject("error");
        err.put("code", code);
        err.put("message", message);
        if (detail != null) {
            err.put("data", detail);
        }
        writeJson(resp, out);
    }

    /** JSON-RPC-Error mit strukturiertem {@code data} (z. B. supported-Versionsliste). */
    private void sendErrorData(HttpServletResponse resp, int code, String message, JsonNode data,
                               JsonNode id, int httpStatus) throws IOException {
        resp.setStatus(httpStatus);
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id != null ? id : NullNode.getInstance());
        ObjectNode err = out.putObject("error");
        err.put("code", code);
        err.put("message", message);
        err.set("data", data);
        writeJson(resp, out);
    }

    /** HTTP 403 bei fremdem/ungültigem Origin-Header (DNS-Rebinding-Schutz). */
    private void sendForbidden(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        PrintWriter w = resp.getWriter();
        w.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + ERR_INVALID_REQUEST
                + ",\"message\":\"Forbidden: Origin header does not match the server\"}}");
        w.flush();
    }

    /**
     * Agent-Kommunikationsfehler als JSON-RPC-Error melden. HTTP 401/403
     * vom Agent (z. B. ACL von mcp_tools.nsf, fehlende Domino-Auth)
     * werden als {@code MCP_1004} ({@code -32003}) gemeldet; alles andere
     * bleibt {@code MCP_1003} ({@code -32001}).
     */
    private void sendAgentError(HttpServletResponse resp, JsonNode id, IOException e) throws IOException {
        if (e instanceof NotesAgentException) {
            int status = ((NotesAgentException) e).getHttpStatus();
            if (status == 401) {
                sendError(resp, ERR_AUTH_REQUIRED, "MCP_1004: Domino authentication failed",
                        "The calling user is not authenticated or has no "
                                + "access to mcp_tools.nsf: " + e.getMessage(), id);
                return;
            }
            if (status == 403) {
                sendError(resp, ERR_AUTH_REQUIRED, "MCP_1004: access to mcp_tools.nsf denied",
                        "The calling user does not have sufficient privileges: " + e.getMessage(), id);
                return;
            }
            if (status == 413) {
                sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                        "Agent rejected the payload (too large): " + e.getMessage(), id);
                return;
            }
        }
        sendError(resp, ERR_AGENT_UNREACHABLE, "MCP_1003: Notes agent unreachable",
                String.valueOf(e.getMessage()), id);
    }

    private void writeJson(HttpServletResponse resp, JsonNode node) throws IOException {
        PrintWriter w = resp.getWriter();
        w.write(mapper.writeValueAsString(node));
        w.flush();
    }

    /**
     * Liest den Request-Body byte-exakt (keine Zeilen-Normalisierung —
     * für das Datei-Handover muss der Body 1:1 dem ankommen, was der Agent
     * über {@code Request_Content} erhalten würde) und prüft die Größen-
     * Guardrails:
     *
     * <ul>
     *   <li>{@code declared != received} (bei vorhandenem Content-Length) →
     *       {@link BodySizeException}: Datenverlust/Replay des
     *       Domino-HTTP-Tasks (64-KB-Buffer-Zone) — der Body wird abgewiesen.</li>
     *   <li>{@code received > maxPayloadBytes} → {@link BodySizeException}:
     *       Fast-Fail vor dem rohen HTTP-500-HTML, den der HTTP-Task bei
     *       {@code Content-Length ≥ 10 240 000} liefern würde.</li>
     * </ul>
     */
    private String readBody(HttpServletRequest req) throws IOException {
        // getContentLength() (int; -1 = unbekannt/Chunked): Domino-JSDK ist
        // Servlet 2.4/2.5 — getContentLengthLong() (3.1+) gibt es dort nicht.
        // int reicht: Payload-Grenze (10 MB) << Integer.MAX_VALUE.
        long declared = req.getContentLength();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        long received = 0;
        try (InputStream in = req.getInputStream()) {
            while ((n = in.read(block)) != -1) {
                buf.write(block, 0, n);
                received += n;
            }
        } catch (IOException e) {
            // Container-Verhalten bei Early-EOF (Verbindung vor Erreichen der
            // Content-Length geschlossen, z. B. Jetty: EofException): Read wirft
            // statt EOF. Partieller Empfang bei vorhandenem Content-Length ist
            // gleichbedeutend mit declared != received → Body abweisen.
            if (declared >= 0 && received < declared) {
                throw new BodySizeException("Request body corrupt (declared " + declared
                        + " != received " + received + " bytes; Domino HTTP-task buffer zone)", false);
            }
            throw e;
        }
        if (declared >= 0 && received != declared) {
            throw new BodySizeException("Request body corrupt (declared " + declared
                    + " != received " + received + " bytes; Domino HTTP-task buffer zone)", false);
        }
        if (received > maxPayloadBytes) {
            throw new BodySizeException("Payload too large: " + received + " > " + maxPayloadBytes
                    + " bytes (raise server HTTP limit + mcpMaxPayloadBytes)", true);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= 300 ? t : t.substring(0, 300) + "...";
    }
}
