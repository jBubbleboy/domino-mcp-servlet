import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    static AgentStub.Handler defaultAgent() {
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
            if ("reslist".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"resources\":["
                        + "{\"uri\":\"domino://AAAAAAAAAAAAAAAA/(All)\","
                        + "\"name\":\"View (All)\","
                        + "\"description\":\"Alle Dokumente\","
                        + "\"mimeType\":\"application/json\"}]}");
            }
            if ("resread".equals(op)) {
                String uri = (body != null && body.get("uri") != null) ? body.get("uri").asText() : "";
                if ("domino://AAAAAAAAAAAAAAAA/(All)".equals(uri)) {
                    return AgentStub.Response.ok(
                            "{\"status\":\"ok\",\"contents\":["
                            + "{\"uri\":\"domino://AAAAAAAAAAAAAAAA/(All)\","
                            + "\"mimeType\":\"application/json\","
                            + "\"text\":\"[{\\\"UNID\\\":\\\"A1\\\"}]\"}]}");
                }
                return AgentStub.Response.ok(
                        "{\"status\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\","
                        + "\"message\":\"Ressource nicht gefunden\"}");
            }
            if ("promptlist".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"prompts\":["
                        + "{\"name\":\"summarize\",\"description\":\"Fasst zusammen\","
                        + "\"arguments\":[{\"name\":\"topic\",\"description\":\"Thema\","
                        + "\"required\":true}]}]}");
            }
            if ("promptget".equals(op)) {
                String name = (body != null && body.get("name") != null) ? body.get("name").asText() : "";
                if ("summarize".equals(name)) {
                    JsonNode args = (body != null && body.get("arguments") != null)
                            ? body.get("arguments") : MAPPER.nullNode();
                    JsonNode topic = args.get("topic");
                    String topicStr = (topic != null && topic.isTextual()) ? topic.asText() : "";
                    return AgentStub.Response.ok(
                            "{\"status\":\"ok\","
                            + "\"messages\":[{\"role\":\"user\","
                            + "\"content\":{\"type\":\"text\","
                            + "\"text\":\"Fasse zusammen: " + topicStr + "\"}}]}");
                }
                return AgentStub.Response.ok(
                        "{\"status\":\"error\",\"code\":\"PROMPT_NOT_FOUND\","
                        + "\"message\":\"Prompt nicht gefunden\"}");
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
        assertEquals("Domino", result.get("serverInfo").get("name").asText());
        assertEquals("2.4.0", result.get("serverInfo").get("version").asText());
        assertFalse(result.has("resultType"),
                "Legacy result must not contain a resultType field");
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
        assertNotNull(view, "@View is missing from the tool list");
        assertEquals("object", view.get("outputSchema").get("type").asText(),
                "outputSchema must be passed through 1:1 from the agent tool list");
        assertEquals("string", view.get("outputSchema").get("properties").get("docs").get("type").asText());
        assertEquals(true, view.get("annotations").get("readOnlyHint").asBoolean(),
                "annotations must be passed through 1:1 from the agent tool list");
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
                "A field on the params level (beside arguments) must be forwarded to the agent");
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
                "Fields in arguments take precedence over fields on the params level");
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
        assertNotNull(structured, "structuredContent is missing in the tool result");
        assertTrue(structured.isObject(), "structuredContent must be a JSON object");
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
        assertNotNull(structured, "structuredContent is missing for tool errors");
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
                    "after agent failure the cache must be delivered as fallback");
        } finally {
            DominoMcpServlet.TOOLS_CACHE_TTL_MS = oldTtl;
            agentStub.setHandler(defaultAgent());
        }
    }

    // ---------- Ressourcen & Prompts ----------

    @Test
    void initializeAdvertisesResourcesAndPrompts() throws Exception {
        Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\"},\"id\":100}");
        assertEquals(200, r.status);
        JsonNode caps = parse(r).get("result").get("capabilities");
        assertNotNull(caps.get("resources"), "capabilities.resources is missing");
        assertEquals(false, caps.get("resources").get("subscribe").asBoolean());
        assertNotNull(caps.get("prompts"), "capabilities.prompts is missing");
    }

    @Test
    void resourcesListComesFromAgent() throws Exception {
        Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"resources/list\",\"id\":101}");
        assertEquals(200, r.status);
        JsonNode resources = parse(r).get("result").get("resources");
        assertEquals(1, resources.size());
        assertEquals("domino://AAAAAAAAAAAAAAAA/(All)", resources.get(0).get("uri").asText());
        assertEquals("View (All)", resources.get(0).get("name").asText());
        assertEquals("application/json", resources.get(0).get("mimeType").asText());
    }

    @Test
    void resourcesReadReturnsContents() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"domino://AAAAAAAAAAAAAAAA/(All)\"},\"id\":102}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode contents = parse(r).get("result").get("contents");
        assertEquals(1, contents.size());
        assertEquals("domino://AAAAAAAAAAAAAAAA/(All)", contents.get(0).get("uri").asText());
        // Der Agent hat die URI wirklich bekommen:
        assertEquals("domino://AAAAAAAAAAAAAAAA/(All)",
                agentStub.lastBody.get("uri").asText());
    }

    @Test
    void resourcesReadUnknownUriIsJsonRpcError() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"nope://keine\"},\"id\":103}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode error = parse(r).get("error");
        assertEquals(-32602, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains("Unknown resource"));
    }

    /**
     * v2.3.0: Eine URI, die nicht gelistet ist, aber in einer vorherigen
     * resources/read-Antwort als {@code resource_link}-Content-Item
     * referenziert wurde, darf gelesen werden (Hub-Ressource schaltet
     * ausgeblendete Ressourcen frei). Beliebige andere URIs bleiben mit
     * -32602 abgewiesen. v2.4.0: Das {@code resource_link}-Item selbst
     * wird dem Client nicht mitgeschickt (Filter), der Unlock bleibt.
     * Läuft auf einer frischen Servlet (frische Registry),
     * weil der reslist-Cache der geteilten Servlet sonst die frische
     * Agent-Handler-Antwort verdecken würde.
     */
    @Test
    void resourcesReadAcceptsUriReferencedByResourceLink() throws Exception {
        final String hubUri = "domino://mcp/guide/resource-hub";
        final String hiddenUri = "domino://mcp/skill/lotusscript";
        agentStub.setHandler((method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("reslist".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"resources\":["
                        + "{\"uri\":\"" + hubUri + "\","
                        + "\"name\":\"Resource-Hub\","
                        + "\"description\":\"Index der ausgeblendeten Ressourcen\","
                        + "\"mimeType\":\"text/markdown\"}]}");
            }
            if ("resread".equals(op)) {
                String uri = (body.get("uri") != null) ? body.get("uri").asText() : "";
                if (hubUri.equals(uri)) {
                    return AgentStub.Response.ok(
                            "{\"status\":\"ok\",\"contents\":["
                            + "{\"uri\":\"" + hubUri + "\","
                            + "\"mimeType\":\"text/markdown\","
                            + "\"text\":\"# Ausgeblendete Ressourcen\\n\"},"
                            + "{\"type\":\"resource_link\",\"uri\":\"" + hiddenUri + "\","
                            + "\"name\":\"LotusScript\",\"description\":\"Skill\","
                            + "\"mimeType\":\"text/markdown\"}]}");
                }
                if (hiddenUri.equals(uri)) {
                    return AgentStub.Response.ok(
                            "{\"status\":\"ok\",\"contents\":["
                            + "{\"uri\":\"" + hiddenUri + "\","
                            + "\"mimeType\":\"text/markdown\","
                            + "\"text\":\"geheimer content\"}]}");
                }
                return AgentStub.Response.ok(
                        "{\"status\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\","
                        + "\"message\":\"Ressource nicht gefunden\"}");
            }
            return AgentStub.Response.ok(
                    "{\"status\":\"error\",\"code\":\"BAD_OP\",\"message\":\"unbekannte op\"}");
        });
        try {
            String url = startServletWithAgent();

            // 1) Hub ist gelistet => Read erlaubt; resource_link wird dem
            //    Client NICHT mitgeschickt (v2.4.0), das Text-Item (Index)
            //    kommt an; der Unlock bleibt wirksam (Schritt 2).
            Resp r1 = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\","
                    + "\"params\":{\"uri\":\"" + hubUri + "\"},\"id\":201}");
            assertEquals(200, r1.status, r1.body);
            JsonNode c1 = parse(r1).get("result").get("contents");
            assertEquals(1, c1.size());
            assertEquals(hubUri, c1.get(0).get("uri").asText());
            assertEquals("text/markdown", c1.get(0).get("mimeType").asText());
            assertFalse(c1.get(0).has("type"));

            // 2) Ausgeblendete URI ist nicht gelistet, aber per Link referenziert => Read erlaubt.
            Resp r2 = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\","
                    + "\"params\":{\"uri\":\"" + hiddenUri + "\"},\"id\":202}");
            assertEquals(200, r2.status, r2.body);
            assertEquals("geheimer content",
                    parse(r2).get("result").get("contents").get(0).get("text").asText());

            // 3) Beliebige URI (weder gelistet noch referenziert) bleibt abgewiesen.
            Resp r3 = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\","
                    + "\"params\":{\"uri\":\"nope://keine\"},\"id\":203}");
            assertEquals(200, r3.status);
            JsonNode error = parse(r3).get("error");
            assertEquals(-32602, error.get("code").asInt());
            assertTrue(error.get("message").asText().contains("Unknown resource"));
        } finally {
            agentStub.setHandler(defaultAgent());
        }
    }

    @Test
    void resourcesReadAgentDownIsMcp1003() throws Exception {
        // Frisches Jetty OHNE Agent-Stub: Der Agent-Aufruf erhält HTTP 404.
        String url = startServletOnly();
        Resp r = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"domino://x/(All)\"},\"id\":107}");
        JsonNode error = parse(r).get("error");
        assertEquals(-32001, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains("MCP_1003"));
    }

    @Test
    void promptsListComesFromAgent() throws Exception {
        Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"prompts/list\",\"id\":104}");
        assertEquals(200, r.status);
        JsonNode prompts = parse(r).get("result").get("prompts");
        assertEquals(1, prompts.size());
        assertEquals("summarize", prompts.get(0).get("name").asText());
        JsonNode args = prompts.get(0).get("arguments");
        assertEquals("topic", args.get(0).get("name").asText());
        assertEquals(true, args.get(0).get("required").asBoolean());
    }

    @Test
    void promptsGetReturnsMessages() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"summarize\",\"arguments\":{\"topic\":\"MCP\"}},\"id\":105}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode messages = parse(r).get("result").get("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("Fasse zusammen: MCP",
                messages.get(0).get("content").get("text").asText());
        // Der Agent hat Name + Argumente wirklich bekommen:
        assertEquals("summarize", agentStub.lastBody.get("name").asText());
        assertEquals("MCP", agentStub.lastBody.get("arguments").get("topic").asText());
    }

    @Test
    void promptsGetUnknownPromptIsJsonRpcError() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"nope\",\"arguments\":{}},\"id\":106}";
        Resp r = post(mcpUrl, body);
        assertEquals(200, r.status);
        JsonNode error = parse(r).get("error");
        assertEquals(-32602, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains("Unknown prompt"));
    }

    // ---------- Per-User-Domino-Authentifizierung (v2.2.0) ----------

    /** Startet frisches Jetty mit Servlet + Agent-Stub, der immer {@code status} antwortet. */
    private static String startServletWithRejectedAgent(int status) throws Exception {
        AgentStub rejectedStub = new AgentStub();
        rejectedStub.setHandler(
                (m, body) -> new AgentStub.Response(status, "Login required"));
        Server s = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new DominoMcpServlet()), "/mcp/servlet");
        ctx.addServlet(new ServletHolder(rejectedStub), "/mcp_tools.nsf/McpGateway");
        s.setHandler(ctx);
        s.start();
        SERVERS.add(s);
        return s.getURI().resolve("/mcp/servlet").toString();
    }

    @Test
    void agent401IsMcp1004AuthError() throws Exception {
        // Wie in Produktion: mcp_tools.nsf-ACL verbietet den anonymen Agent-Call.
        String url = startServletWithRejectedAgent(401);
        Resp r = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":11}");
        JsonNode error = parse(r).get("error");
        assertEquals(-32003, error.get("code").asInt(),
                "Agent 401 must be reported as MCP_1004 (-32003)");
        assertTrue(error.get("message").asText().contains("MCP_1004"));
    }

    @Test
    void agent403IsMcp1004ForbiddenError() throws Exception {
        String url = startServletWithRejectedAgent(403);
        Resp r = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":12}");
        JsonNode error = parse(r).get("error");
        assertEquals(-32003, error.get("code").asInt(),
                "Agent 403 must be reported as MCP_1004 (-32003)");
        assertTrue(error.get("message").asText().contains("MCP_1004"));
    }

    @Test
    void agentOtherErrorsStayMcp1003() throws Exception {
        // Regression: Nicht-Auth-Fehler bleiben MCP_1003 (-32001).
        String url = startServletWithRejectedAgent(500);
        Resp r = post(url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":13}");
        JsonNode error = parse(r).get("error");
        assertEquals(-32001, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains("MCP_1003"));
    }

    // ---------- Servlet-Config via vConfig (op=env, s. refreshEnvConfig) ----------

    private static class EnvCtx {
        final DominoMcpServlet servlet = new DominoMcpServlet();
        String url;
    }

    /** Startet frisches Jetty mit eigener Servlet-Instanz + Agent-Stub (Env frei wählbar). */
    private static EnvCtx startServletWithEnvStub(AgentStub stub) throws Exception {
        EnvCtx ctx = new EnvCtx();
        Server s = new Server(0);
        ServletContextHandler c = new ServletContextHandler();
        c.setContextPath("/");
        c.addServlet(new ServletHolder(ctx.servlet), "/mcp/servlet");
        c.addServlet(new ServletHolder(stub), "/mcp_tools.nsf/McpGateway");
        s.setHandler(c);
        s.start();
        SERVERS.add(s);
        ctx.url = s.getURI().resolve("/mcp/servlet").toString();
        return ctx;
    }

    @Test
    void envValuesFromAgentOverrideServletDefaults() throws Exception {
        // vConfig (per op=env) gewinnt über web.xml/Default; Werte 1:1 (kein Clamp).
        AgentStub stub = new AgentStub();
        stub.setHandler((method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("env".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"env\":{\"fileCallDir\":\"/tmp/envtest/\",\"maxFileCallBytes\":10239999,"
                        + "\"agentTimeoutMs\":60000,\"maxPayloadBytes\":1234567}}");
            }
            if ("list".equals(op)) {
                return AgentStub.Response.ok("{\"status\":\"ok\",\"tools\":[]}");
            }
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });
        FileCallEnv.invalidate();
        EnvCtx ctx = startServletWithEnvStub(stub);
        try {
            Resp r = post(ctx.url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
            assertEquals(200, r.status);
            assertTrue(parse(r).has("result"), "tools/list muss ok sein: " + r.body);
            assertEquals(60000, ctx.servlet.agentTimeoutMs(),
                    "vConfig-Timeout wird 1:1 uebernommen");
            assertEquals(1234567L, ctx.servlet.maxPayloadBytes(),
                    "vConfig-Payload-Limit wird 1:1 uebernommen");
        } finally {
            FileCallEnv.invalidate();
        }
    }

    @Test
    void envServerNameFromAgentOverridesDefault() throws Exception {
        // vConfig SERVER_NAME (per op=env) gewinnt über den Servlet-Default.
        AgentStub stub = new AgentStub();
        stub.setHandler((method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("env".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"env\":{\"fileCallDir\":\"/tmp/envtest/\",\"maxFileCallBytes\":10239999,"
                        + "\"serverName\":\"DominoQA\"}}");
            }
            if ("list".equals(op)) {
                return AgentStub.Response.ok("{\"status\":\"ok\",\"tools\":[]}");
            }
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });
        FileCallEnv.invalidate();
        EnvCtx ctx = startServletWithEnvStub(stub);
        try {
            Resp r = post(ctx.url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
            assertEquals(200, r.status);
            assertEquals("DominoQA", ctx.servlet.serverName(),
                    "vConfig-Server-Name wird 1:1 uebernommen");
        } finally {
            FileCallEnv.invalidate();
        }
    }

    @Test
    void initializeRefreshesServerNameFromAgent() throws Exception {
        // initialize triggert den Env-Refresh: serverInfo.name traegt den
        // vConfig-Wert (serverName) ab dem allerersten Request (Handshake).
        AgentStub stub = new AgentStub();
        stub.setHandler((method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("env".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"env\":{\"fileCallDir\":\"/tmp/envtest/\",\"maxFileCallBytes\":10239999,"
                        + "\"serverName\":\"DominoQA\"}}");
            }
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });
        FileCallEnv.invalidate();
        EnvCtx ctx = startServletWithEnvStub(stub);
        try {
            Resp r = post(ctx.url,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}},\"id\":1}");
            assertEquals(200, r.status);
            assertEquals("DominoQA",
                    parse(r).get("result").get("serverInfo").get("name").asText(),
                    "initialize: serverInfo.name aus der vConfig (per op=env)");
        } finally {
            FileCallEnv.invalidate();
        }
    }

    @Test
    void envWithoutServletConfigFieldsKeepsDefaults() throws Exception {
        // Älterer Agent ohne die neuen Env-Felder: Servlet behält web.xml/Default.
        AgentStub stub = new AgentStub();
        stub.setHandler((method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("env".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"env\":{\"fileCallDir\":\"/tmp/envtest/\",\"maxFileCallBytes\":10239999}}");
            }
            if ("list".equals(op)) {
                return AgentStub.Response.ok("{\"status\":\"ok\",\"tools\":[]}");
            }
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });
        FileCallEnv.invalidate();
        EnvCtx ctx = startServletWithEnvStub(stub);
        try {
            Resp r = post(ctx.url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
            assertEquals(200, r.status);
            assertEquals(DominoMcpServlet.AGENT_TIMEOUT_MS_DEFAULT, ctx.servlet.agentTimeoutMs());
            assertEquals(DominoMcpServlet.MAX_PAYLOAD_BYTES_DEFAULT, ctx.servlet.maxPayloadBytes());
        } finally {
            FileCallEnv.invalidate();
        }
    }

    @Test
    void envFailureFallsBackSilently() throws Exception {
        // Agent antwortet auf op=env mit Fehler (z. B. env nicht implementiert):
        // Fail-Open — Defaults bleiben, der Request selbst schlaegt nicht fehl.
        AgentStub stub = new AgentStub();
        stub.setHandler((method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("env".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"error\",\"code\":\"BAD_OP\",\"message\":\"unbekannte op\"}");
            }
            if ("list".equals(op)) {
                return AgentStub.Response.ok("{\"status\":\"ok\",\"tools\":[]}");
            }
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });
        FileCallEnv.invalidate();
        EnvCtx ctx = startServletWithEnvStub(stub);
        try {
            Resp r = post(ctx.url, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
            assertEquals(200, r.status);
            assertTrue(parse(r).has("result"), "Fail-Open: tools/list trotz Env-Fehler ok: " + r.body);
            assertEquals(DominoMcpServlet.AGENT_TIMEOUT_MS_DEFAULT, ctx.servlet.agentTimeoutMs());
            assertEquals(DominoMcpServlet.MAX_PAYLOAD_BYTES_DEFAULT, ctx.servlet.maxPayloadBytes());
        } finally {
            FileCallEnv.invalidate();
        }
    }

    @Test
    void authForAgentCallForwardsHeaderOnlyForAuthenticatedRequests() {
        HttpServletRequest authed = mock(HttpServletRequest.class);
        when(authed.getRemoteUser()).thenReturn("mcp_user/Domain");
        when(authed.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        assertEquals("Basic dXNlcjpwYXNz", DominoMcpServlet.authForAgentCall(authed),
                "Authenticated request: header is passed through for the agent call");

        HttpServletRequest anonymous = mock(HttpServletRequest.class);
        when(anonymous.getRemoteUser()).thenReturn(null);
        when(anonymous.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        assertEquals(null, DominoMcpServlet.authForAgentCall(anonymous),
                "Anonymous request: header must NOT be forwarded (anti-spoofing)");

        HttpServletRequest authedWithoutHeader = mock(HttpServletRequest.class);
        when(authedWithoutHeader.getRemoteUser()).thenReturn("mcp_user/Domain");
        when(authedWithoutHeader.getHeader("Authorization")).thenReturn(null);
        assertEquals(null, DominoMcpServlet.authForAgentCall(authedWithoutHeader));
    }
}
