import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
 * INI-Datei, keine Init-Parameter). Die Servlet spricht immer den Agent
 * {@code McpGateway} in der Datenbank {@code mcp_tools.nsf} direkt im
 * Web-Root an — auf dem <b>dieselben Server</b>, auf dem der MCP-Request
 * eingeht (Host/Scheme/Port werden aus dem einkommenden Request abgeleitet).
 * Damit läuft die Servlet ohne jede Einrichtung von außen korrekt:</p>
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
 * <p>Unterstützte MCP-Methoden:</p>
 * <ul>
 *   <li>{@code initialize} — Protokoll-Handshake (serverInfo, capabilities)</li>
 *   <li>{@code tools/list} — Tool-Definitionen, dynamisch aus der Notes-DB
 *       (geliefert vom Agent, in der Servlet mit TTL gecacht)</li>
     *   <li>{@code tools/call} — Tool-Name + Argumente werden an den Agent
     *       weitergeleitet; dessen JSON-Antwort wird als MCP-Tool-Result zurückgegeben.
     *       Toleranz: zusätzliche Felder auf der params-Ebene (neben
     *       {@code name}/{@code arguments}, z.&nbsp;B. ein dort abgelegter
     *       {@code cursor}) werden mitübergeben, damit ein Folge-Call nicht
     *       versehentlich neu gestartet wird; Felder in {@code arguments}
     *       haben Vorrang.</li>
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

    /** Connect/Read-Timeout je Agent-Call in Millisekunden. */
    static final int AGENT_TIMEOUT_MS = 30_000;

    /** TTL der Tool-Liste-Caches in Millisekunden (Tests dürfen anpassen). */
    static volatile long TOOLS_CACHE_TTL_MS = 60_000L;

    static final String SERVER_NAME = "domino-mcp-server";
    static final String SERVER_VERSION = "2.0.0";
    static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    // JSON-RPC-Error-Codes
    static final int ERR_PARSE = -32700;
    static final int ERR_INVALID_REQUEST = -32600;
    static final int ERR_METHOD_NOT_FOUND = -32601;
    static final int ERR_INVALID_PARAMS = -32602;
    static final int ERR_AGENT_UNREACHABLE = -32001; // MCP_1003

    private final NotesAgentClient agentClient = new NotesAgentClient();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- HTTP ----------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        resp.setContentType("application/json; charset=utf-8");
        PrintWriter w = resp.getWriter();
        w.write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + ERR_METHOD_NOT_FOUND
                + ",\"message\":\"Nur POST mit JSON-RPC 2.0 wird unterstuetzt\"},\"id\":null}");
        w.flush();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // charset= im Content-Type: setCharacterEncoding(String) ist erst Servlet 3.0,
        // das Domino-JSDK implementiert nur Servlet 2.4 (NoSuchMethodError sonst).
        resp.setContentType("application/json; charset=utf-8");

        String body = readBody(req);
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            sendError(resp, ERR_PARSE, "Parse error", "Ungültiges JSON in Request: " + e.getMessage(), null);
            return;
        }
        if (root == null || !root.isObject()) {
            sendError(resp, ERR_INVALID_REQUEST, "Invalid Request",
                    "Request-Root muss ein JSON-Objekt sein", null);
            return;
        }

        JsonNode id = root.get("id");
        JsonNode methodNode = root.get("method");
        if (methodNode == null || !methodNode.isTextual() || methodNode.asText().isEmpty()) {
            sendError(resp, ERR_INVALID_REQUEST, "Invalid Request",
                    "Feld 'method' (nicht-leerer String) fehlt", id);
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

        try {
            switch (method) {
                case "initialize":
                    handleInitialize(resp, id, params);
                    break;
                case "tools/list":
                    handleToolsList(resp, id, agentBase);
                    break;
                case "tools/call":
                    handleToolsCall(resp, id, params, agentBase);
                    break;
                default:
                    sendError(resp, ERR_METHOD_NOT_FOUND, "Method not found",
                            "Methode wird nicht unterstützt: " + method, id);
            }
        } catch (IOException e) {
            // Sicherheitshalber: ungeplante I/O-Fehler -> JSON-RPC-Error statt leerer Antwort
            sendError(resp, ERR_AGENT_UNREACHABLE, "MCP_1003: interner I/O-Fehler",
                    String.valueOf(e.getMessage()), id);
        }
    }

    /**
     * Agent-Base-URL: immer der Server des einkommenden Requests
     * (Scheme + Host [+ Nicht-Standard-Port]), dahinter die festen
     * {@code /mcp_tools.nsf/McpGateway} (ohne Query-String).
     */
    static String agentBaseUrl(HttpServletRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.getScheme()).append("://").append(req.getServerName());
        int port = req.getServerPort();
        boolean defaultPort = ("http".equals(req.getScheme()) && port == 80)
                || ("https".equals(req.getScheme()) && port == 443);
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        sb.append('/').append(AGENT_DATABASE).append('/').append(AGENT_NAME);
        return sb.toString();
    }

    // ---------- Method-Handler ----------

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
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        sendResult(resp, id, result);
    }

    private void handleToolsList(HttpServletResponse resp, JsonNode id, String agentBase) throws IOException {
        List<ToolRegistry.ToolDefinition> tools;
        try {
            tools = toolRegistry.getTools(agentClient, agentBase, AGENT_TIMEOUT_MS, TOOLS_CACHE_TTL_MS);
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
        sendResult(resp, id, result);
    }

    private void handleToolsCall(HttpServletResponse resp, JsonNode id, JsonNode params,
                                 String agentBase) throws IOException {
        if (params == null || !params.isObject()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "tools/call benötigt 'params' als Objekt mit 'name' und optional 'arguments'", id);
            return;
        }
        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().trim().isEmpty()) {
            sendError(resp, ERR_INVALID_PARAMS, "Invalid params",
                    "Feld 'params.name' (Tool-Name) fehlt", id);
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
            known = toolRegistry.getTools(agentClient, agentBase, AGENT_TIMEOUT_MS, TOOLS_CACHE_TTL_MS);
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
                    "Tool ist nicht in der Notes-DB definiert: " + toolName, id);
            return;
        }

        // Tool-Call an den Agent weiterleiten.
        String agentJson;
        try {
            agentJson = agentClient.callTool(agentBase, AGENT_TIMEOUT_MS, toolName, arguments);
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
                textItem.put("text", "Agent lieferte eine leere Antwort");
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
            textItem.put("text", "Agent lieferte kein gültiges JSON: " + truncate(agentJson));
            isError = true;
        }
        result.put("isError", isError);
        sendResult(resp, id, result);
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

    private void sendError(HttpServletResponse resp, int code, String message, String detail, JsonNode id)
            throws IOException {
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

    private void sendAgentError(HttpServletResponse resp, JsonNode id, IOException e) throws IOException {
        sendError(resp, ERR_AGENT_UNREACHABLE, "MCP_1003: Notes-Agent nicht erreichbar",
                String.valueOf(e.getMessage()), id);
    }

    private void writeJson(HttpServletResponse resp, JsonNode node) throws IOException {
        PrintWriter w = resp.getWriter();
        w.write(mapper.writeValueAsString(node));
        w.flush();
    }

    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= 300 ? t : t.substring(0, 300) + "...";
    }
}
