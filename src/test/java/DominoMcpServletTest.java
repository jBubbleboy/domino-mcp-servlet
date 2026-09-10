import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrationstests: Das Servlet läuft in einem lokalen Jetty, der
 * Notes-Web-Agent wird von {@link AgentStub} simuliert — eine Servlet, die
 * im selben Jetty unter /mcp_tools.nsf/McpGateway gemountet ist. Genau wie
 * im Produktivbetrieb: Die Servlet leitet den Agent-Host aus dem
 * einkommenden Request ab (keine externe Konfiguration). So wird der
 * komplette Pfad LLM (JSON-RPC) -> Servlet -> Agent (HTTP POST, JSON-Body)
 * mit echtem HTTP und echtem JSON-Handling abgedeckt.
 */
class DominoMcpServletTest {

    private static AgentStub agentStub;
    private static String mcpUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Server> SERVERS = new ArrayList<Server>();

    /** Standard-Agent: liefert @View/@Echo/@Error; @View und @Error antworten fix. */
    private static AgentStub.Handler defaultAgent() {
        return (method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("list".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"tools\":["
                        + "{\"name\":\"@View\","
                        + "\"description\":\"Dokumente aus einer ansicht\","
                        + "\"inputSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"viewName\":{\"type\":\"string\"}}},"
                        + "\"outputSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"docs\":{\"type\":\"string\"}}},"
                        + "\"annotations\":{\"readOnlyHint\":true}},"
                        + "{\"name\":\"@Echo\",\"description\":\"Gibt Parameter zurueck\"},"
                        + "{\"name\":\"@Error\",\"description\":\"Simuliert einen Fehler\"}]}");
            }
            if ("call".equals(op)) {
                String tool = (body != null && body.get("tool") != null) ? body.get("tool").asText() : "";
                if ("@View".equals(tool)) {
                    return AgentStub.Response.ok(
                            "{\"status\":\"ok\",\"tool\":\"@View\","
                            + "\"data\":{\"documents\":["
                            + "{\"UNID\":\"A1\",\"Subject\":\"Hallo\"}]},"
                            + "\"pagination\":{\"total\":1,\"returned\":1,"
                            + "\"skip\":0,\"hasMore\":false}}");
                }
                if ("@Error".equals(tool)) {
                    return AgentStub.Response.ok(
                            "{\"status\":\"error\",\"code\":\"DOMINO_4040\","
                            + "\"message\":\"Dokument nicht gefunden\"}");
                }
                JsonNode params = (body != null && body.get("params") != null)
                        ? body.get("params") : MAPPER.nullNode();
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"tool\":\"" + tool
                        + "\",\"echoParams\":" + params + "}");
            }
            return AgentStub.Response.ok(
                    "{\"status\":\"error\",\"code\":\"BAD_OP\","
                    + "\"message\":\"unbekannte op\"}");
        };
    }

    @BeforeAll
    static void up() throws Exception {
        agentStub = new AgentStub();
        agentStub.setHandler(defaultAgent());
        mcpUrl = startServletWithAgent();
    }

    @AfterAll
    static void down() throws Exception {
        for (Server s : SERVERS) {
            s.stop();
        }
    }

    // ---------- Test-Setup-Helfer ----------

    /** Startet frisches Jetty NUR mit der Servlet (kein Agent-Stub). */
    private static String startServletOnly() throws Exception {
        Server s = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new DominoMcpServlet()), "/mcp/servlet");
        s.setHandler(ctx);
        s.start();
        SERVERS.add(s);
        return s.getURI().resolve("/mcp/servlet").toString();
    }

    /** Startet die Servlet + den Agent-Stub in einem Jetty. */
    private static String startServletWithAgent() throws Exception {
        Server s = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new DominoMcpServlet()), "/mcp/servlet");
        ctx.addServlet(new ServletHolder(agentStub), "/mcp_tools.nsf/McpGateway");
        s.setHandler(ctx);
        s.start();
        SERVERS.add(s);
        return s.getURI().resolve("/mcp/servlet").toString();
    }

    static class Resp {
        int status;
        String body;
    }

    static Resp post(String url, String json) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int status = con.getResponseCode();
        InputStream in = status >= 400 ? con.getErrorStream() : con.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        Resp r = new Resp();
        r.status = status;
        r.body = sb.toString();
        return r;
    }

    private static JsonNode parse(Resp r) throws IOException {
        return MAPPER.readTree(r.body);
    }

    // ---------- Tests ----------

    @Test
    void initializeReturnsServerInfoAndEchoesProtocolVersion() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\","
                + "\"capabilities\":{},\"clientInfo\":"
                + "{\"name\":\"test\",\"version\":\"1\"}},\"id\":1}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode root = parse(r);
        assertEquals("2.0", root.get("jsonrpc").asText());
        assertEquals(1, root.get("id").asInt());
        JsonNode result = root.get("result");
        assertEquals("2025-03-26", result.get("protocolVersion").asText());
        assertEquals("domino-mcp-server", result.get("serverInfo").get("name").asText());
        assertEquals("2.0.0", result.get("serverInfo").get("version").asText());
        assertNotNull(result.get("capabilities").get("tools"));
    }

    @Test
    void toolsListComesFromAgent() throws Exception {
        Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":\"7\"}");
        assertEquals(200, r.status);
        JsonNode root = parse(r);
        assertEquals("7", root.get("id").asText());
        JsonNode tools = root.get("result").get("tools");
        assertEquals(3, tools.size());
        assertEquals("@View", tools.get(0).get("name").asText());
        assertEquals("Dokumente aus einer ansicht", tools.get(0).get("description").asText());
        assertEquals("object", tools.get(0).get("inputSchema").get("type").asText());
    }

    @Test
    void toolsListPassesThroughOutputSchemaAndAnnotations() throws Exception {
        Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":\"45\"}");
        assertEquals(200, r.status);
        JsonNode tools = parse(r).get("result").get("tools");
        JsonNode view = null;
        for (JsonNode t : tools) {
            if ("@View".equals(t.get("name").asText())) {
                view = t;
                break;
            }
        }
        assertNotNull(view, "@View fehlt in der Tool-Liste");
        assertEquals("object", view.get("outputSchema").get("type").asText(),
                "outputSchema muss 1:1 von der Agent-Tool-Liste weitergegeben werden");
        assertEquals("string", view.get("outputSchema").get("properties").get("docs").get("type").asText());
        assertEquals(true, view.get("annotations").get("readOnlyHint").asBoolean(),
                "annotations muss 1:1 von der Agent-Tool-Liste weitergegeben werden");
    }

    @Test
    void toolCallReturnsAgentJsonAsContent() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"@View\","
                + "\"arguments\":{\"viewName\":\"(All)\"}},\"id\":42}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode root = parse(r);
        assertEquals(42, root.get("id").asInt());
        JsonNode result = root.get("result");
        assertEquals(false, result.get("isError").asBoolean());
        JsonNode text = result.get("content").get(0);
        assertEquals("text", text.get("type").asText());
        JsonNode agentJson = MAPPER.readTree(text.get("text").asText());
        assertEquals("ok", agentJson.get("status").asText());
        assertEquals("@View", agentJson.get("tool").asText());
        // Der Agent hat die Argumente wirklich bekommen:
        JsonNode sentParams = agentStub.lastBody.get("params");
        assertEquals("(All)", sentParams.get("viewName").asText());
    }

    @Test
    void toolCallMergesParamsLevelFieldIntoAgentParams() throws Exception {
        // 'cursor' liegt NEBEN 'arguments' auf der params-Ebene (nicht
        // konform, aber von Clients schon gesehen): er muss trotzdem an
        // den Agent weitergeleitet werden, sonst erzeugt ein Folge-Call
        // versehentlich einen neuen CM-Doc.
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"@Echo\","
                + "\"arguments\":{\"viewName\":\"(All)\"},"
                + "\"cursor\":\"next-DXRLC4\"},\"id\":46}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode sentParams = agentStub.lastBody.get("params");
        assertEquals("(All)", sentParams.get("viewName").asText());
        assertEquals("next-DXRLC4", sentParams.get("cursor").asText(),
                "Feld auf params-Ebene (neben arguments) muss an den Agent weitergeleitet werden");
    }

    @Test
    void toolCallArgumentsWinOverParamsLevelField() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"@Echo\","
                + "\"arguments\":{\"cursor\":\"next-AAAA\"},"
                + "\"cursor\":\"next-BBBB\"},\"id\":47}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode sentParams = agentStub.lastBody.get("params");
        assertEquals("next-AAAA", sentParams.get("cursor").asText(),
                "Felder in arguments haben Vorrang vor Feldern auf params-Ebene");
    }

    @Test
    void toolCallAgentErrorSetsIsError() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"@Error\",\"arguments\":{}},\"id\":3}";
        Resp r = post(mcpUrl, body);
        JsonNode result = parse(r).get("result");
        assertEquals(true, result.get("isError").asBoolean());
        JsonNode agentJson = MAPPER.readTree(
                result.get("content").get(0).get("text").asText());
        assertEquals("DOMINO_4040", agentJson.get("code").asText());
    }

    @Test
    void toolCallIncludesStructuredContentNextToText() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"@View\","
                + "\"arguments\":{\"viewName\":\"(All)\"}},\"id\":43}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode result = parse(r).get("result");
        JsonNode structured = result.get("structuredContent");
        assertNotNull(structured, "structuredContent fehlt im Tool-Result");
        assertTrue(structured.isObject(), "structuredContent muss ein JSON-Objekt sein");
        assertEquals("ok", structured.get("status").asText());
        assertEquals("@View", structured.get("tool").asText());
        assertEquals("A1",
                structured.get("data").get("documents").get(0).get("UNID").asText());
    }

    @Test
    void toolCallAgentErrorIncludesStructuredContent() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"@Error\",\"arguments\":{}},\"id\":44}";
        Resp r = post(mcpUrl, body);
        JsonNode result = parse(r).get("result");
        assertEquals(true, result.get("isError").asBoolean());
        JsonNode structured = result.get("structuredContent");
        assertNotNull(structured, "structuredContent fehlt bei Tool-Fehlern");
        assertEquals("DOMINO_4040", structured.get("code").asText());
        assertEquals("Dokument nicht gefunden", structured.get("message").asText());
    }

    @Test
    void unknownToolIsJsonRpcError() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"@Nope\",\"arguments\":{}},\"id\":4}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode error = parse(r).get("error");
        assertEquals(-32602, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains("Unknown tool"));
        assertTrue(error.get("data").asText().contains("@Nope"));
    }

    @Test
    void missingToolNameIsJsonRpcError() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"arguments\":{}},\"id\":5}";
        Resp r = post(mcpUrl, body);
        assertEquals(-32602, parse(r).get("error").get("code").asInt());
    }

    @Test
    void unknownMethodIsMethodNotFound() throws Exception {
        Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"foo/bar\",\"id\":6}");
        assertEquals(-32601, parse(r).get("error").get("code").asInt());
    }

    @Test
    void malformedJsonIsParseErrorWithNullId() throws Exception {
        Resp r = post(mcpUrl, "{this is not json");
        JsonNode root = parse(r);
        assertEquals(-32700, root.get("error").get("code").asInt());
        assertTrue(root.get("id").isNull());
    }

    @Test
    void notificationGets202WithoutBody() throws Exception {
        Resp r = post(mcpUrl,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, r.status);
        assertEquals("", r.body.trim());
    }

    @Test
    void agentDownIsMcp1003() throws Exception {
        // Frisches Jetty OHNE Agent-Stub: Der Agent-Aufruf erhält HTTP 404.
        String url = startServletOnly();
        Resp r = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":9}");
        JsonNode error = parse(r).get("error");
        assertEquals(-32001, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains("MCP_1003"));
    }

    @Test
    void toolsListFallsBackToStaleCacheWhenAgentDies() throws Exception {
        long oldTtl = DominoMcpServlet.TOOLS_CACHE_TTL_MS;
        try {
            DominoMcpServlet.TOOLS_CACHE_TTL_MS = 1000L;
            // Cache füllen (frischer Agent-Call).
            post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
            Thread.sleep(1100); // Cache läuft ab
            agentStub.setHandler((m, body) -> new AgentStub.Response(500, "boom"));

            Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":2}");
            assertEquals(3, parse(r).get("result").get("tools").size(),
                    "nach Agent-Ausfall muss der Cache als Fallback geliefert werden");
        } finally {
            DominoMcpServlet.TOOLS_CACHE_TTL_MS = oldTtl;
            agentStub.setHandler(defaultAgent());
        }
    }
}
