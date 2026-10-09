import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrationstests für die Modern-Ära (MCP 2026-07-28): stateless,
 * per-request {@code _meta} (protocolVersion + clientCapabilities),
 * Header-Validierung ({@code MCP-Protocol-Version}, {@code Mcp-Method},
 * {@code Mcp-Name} inkl. Base64-Sentinel), {@code server/discover},
 * {@code resultType}/{@code ttlMs}/{@code cacheScope}, neue HTTP-Statuscodes
 * (400/403/404) und der Origin-Schutz. Wie {@link DominoMcpServletTest}
 * läuft die Servlet in einem lokalen Jetty; der Notes-Agent wird von
 * {@link AgentStub} simuliert.
 */
class DominoMcpServletModernTest {

    private static AgentStub agentStub;
    private static String mcpUrl;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Server> SERVERS = new ArrayList<Server>();
    private static final String MODERN = "2026-07-28";

    @BeforeAll
    static void up() throws Exception {
        agentStub = new AgentStub();
        agentStub.setHandler(DominoMcpServletTest.defaultAgent());
        mcpUrl = startServletWithAgent();
    }

    @AfterAll
    static void down() throws Exception {
        for (Server s : SERVERS) {
            s.stop();
        }
    }

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

    // ---------- Test-Helfer ----------

    static class Resp {
        int status;
        String body;
    }

    static Resp post(String url, String json, String[]... headers) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        for (String[] h : headers) {
            con.setRequestProperty(h[0], h[1]);
        }
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

    private static Resp post(String url, String json) throws IOException {
        return post(url, json, new String[0][]);
    }

    private static JsonNode parse(Resp r) throws IOException {
        return MAPPER.readTree(r.body);
    }

    /**
     * POST per Raw-Socket — nötig, weil {@link HttpURLConnection} den
     * {@code Origin}-Header client-seitig verwirft (JDK-Forbidden-Header).
     *
     * @param origin null → kein Origin-Header (Nicht-Browser-Client)
     */
    private static Resp rawPost(String url, String json, String origin) throws IOException {
        URL u = new URL(url);
        int port = u.getPort() != -1 ? u.getPort() : 80;
        byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);
        String request = "POST " + u.getPath() + " HTTP/1.1\r\n"
                + "Host: " + u.getHost() + ":" + port + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + (origin != null ? "Origin: " + origin + "\r\n" : "")
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try (Socket sock = new Socket(u.getHost(), port)) {
            OutputStream os = sock.getOutputStream();
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.write(bodyBytes);
            os.flush();
            byte[] all = readAll(sock.getInputStream());
            String raw = new String(all, StandardCharsets.UTF_8);
            int sep = raw.indexOf("\r\n\r\n");
            String head = raw.substring(0, sep);
            String respBody = raw.substring(sep + 4);
            String[] statusLine = head.split("\r\n")[0].split(" ");
            Resp r = new Resp();
            r.status = Integer.parseInt(statusLine[1]);
            r.body = respBody;
            return r;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int n;
        while ((n = in.read(block)) != -1) {
            buf.write(block, 0, n);
        }
        return buf.toByteArray();
    }

    private static String[] hdr(String k, String v) {
        return new String[]{k, v};
    }

    private static String[][] modernHeaders(String method) {
        return new String[][]{
                hdr("MCP-Protocol-Version", MODERN),
                hdr("Mcp-Method", method)};
    }

    private static String[][] modernHeaders(String method, String name) {
        return new String[][]{
                hdr("MCP-Protocol-Version", MODERN),
                hdr("Mcp-Method", method),
                hdr("Mcp-Name", name)};
    }

    /**
     * Modern-Request bauen: {@code params._meta} mit protocolVersion +
     * clientCapabilities, optional zusätzliche params-Felder.
     */
    private static String modernRequest(String method, int id, JsonNode extraParams)
            throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", MODERN);
        meta.putObject("io.modelcontextprotocol/clientCapabilities");
        if (extraParams != null) {
            for (Iterator<Map.Entry<String, JsonNode>> it = extraParams.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                params.set(e.getKey(), e.getValue());
            }
        }
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.set("params", params);
        req.put("id", id);
        return req.toString();
    }

    // ---------- Tests ----------

    @Test
    void serverDiscoverReturnsVersionsCapabilitiesAndIdentity() throws Exception {
        Resp r = post(mcpUrl, modernRequest("server/discover", 1, null),
                modernHeaders("server/discover"));
        assertEquals(200, r.status, r.body);
        JsonNode result = parse(r).get("result");
        assertEquals("complete", result.get("resultType").asText());
        JsonNode versions = result.get("supportedVersions");
        assertEquals(1, versions.size());
        assertEquals("2026-07-28", versions.get(0).asText());
        assertNotNull(result.get("capabilities").get("tools"));
        assertNotNull(result.get("capabilities").get("resources"));
        assertNotNull(result.get("capabilities").get("prompts"));
        JsonNode serverInfo = result.get("_meta").get("io.modelcontextprotocol/serverInfo");
        assertEquals("Domino", serverInfo.get("name").asText());
        assertEquals("2.4.0", serverInfo.get("version").asText());
        assertEquals(DominoMcpServlet.TOOLS_CACHE_TTL_MS, result.get("ttlMs").asLong());
        assertEquals("private", result.get("cacheScope").asText());
    }

    @Test
    void toolsListModernCarriesResultTypeServerInfoAndCaching() throws Exception {
        Resp r = post(mcpUrl, modernRequest("tools/list", 2, null), modernHeaders("tools/list"));
        assertEquals(200, r.status, r.body);
        JsonNode result = parse(r).get("result");
        assertEquals(3, result.get("tools").size());
        assertEquals("complete", result.get("resultType").asText());
        assertEquals("Domino",
                result.get("_meta").get("io.modelcontextprotocol/serverInfo").get("name").asText());
        assertEquals(DominoMcpServlet.TOOLS_CACHE_TTL_MS, result.get("ttlMs").asLong());
        assertEquals("private", result.get("cacheScope").asText());
    }

    @Test
    void toolsCallModernCarriesResultTypeButNoCachingFields() throws Exception {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("name", "@View");
        p.set("arguments", MAPPER.createObjectNode().put("viewName", "(All)"));
        Resp r = post(mcpUrl, modernRequest("tools/call", 3, p), modernHeaders("tools/call", "@View"));
        assertEquals(200, r.status, r.body);
        JsonNode result = parse(r).get("result");
        assertEquals("complete", result.get("resultType").asText());
        assertNotNull(result.get("_meta"), "Modern result must carry _meta.serverInfo");
        assertFalse(result.has("ttlMs"), "tools/call is not a cacheable result");
        assertFalse(result.has("cacheScope"), "tools/call is not a cacheable result");
        assertEquals(false, result.get("isError").asBoolean());
    }

    @Test
    void resourcesReadModernCarriesCachingFields() throws Exception {
        String uri = "domino://AAAAAAAAAAAAAAAA/(All)";
        ObjectNode p = MAPPER.createObjectNode();
        p.put("uri", uri);
        Resp r = post(mcpUrl, modernRequest("resources/read", 4, p),
                modernHeaders("resources/read", uri));
        assertEquals(200, r.status, r.body);
        JsonNode result = parse(r).get("result");
        assertEquals(1, result.get("contents").size());
        assertEquals("complete", result.get("resultType").asText());
        assertEquals(DominoMcpServlet.TOOLS_CACHE_TTL_MS, result.get("ttlMs").asLong());
        assertEquals("private", result.get("cacheScope").asText());
    }

    @Test
    void promptsListModernCarriesCachingFields() throws Exception {
        Resp r = post(mcpUrl, modernRequest("prompts/list", 5, null), modernHeaders("prompts/list"));
        assertEquals(200, r.status, r.body);
        JsonNode result = parse(r).get("result");
        assertEquals(1, result.get("prompts").size());
        assertEquals("complete", result.get("resultType").asText());
        assertEquals("private", result.get("cacheScope").asText());
    }

    @Test
    void promptsGetModernCarriesResultTypeButNoCachingFields() throws Exception {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("name", "summarize");
        p.set("arguments", MAPPER.createObjectNode().put("topic", "MCP"));
        Resp r = post(mcpUrl, modernRequest("prompts/get", 6, p), modernHeaders("prompts/get", "summarize"));
        assertEquals(200, r.status, r.body);
        JsonNode result = parse(r).get("result");
        assertEquals(1, result.get("messages").size());
        assertEquals("complete", result.get("resultType").asText());
        assertFalse(result.has("ttlMs"));
        assertFalse(result.has("cacheScope"));
    }

    @Test
    void unsupportedVersionIs32022With400AndSupportedList() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", "2099-01-01");
        meta.putObject("io.modelcontextprotocol/clientCapabilities");
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"params\":" + params + ",\"id\":7}";
        Resp r = post(mcpUrl, body,
                hdr("MCP-Protocol-Version", "2099-01-01"),
                hdr("Mcp-Method", "tools/list"));
        assertEquals(400, r.status);
        JsonNode error = parse(r).get("error");
        assertEquals(-32022, error.get("code").asInt());
        assertEquals("2026-07-28", error.get("data").get("supported").get(0).asText());
        assertEquals("2099-01-01", error.get("data").get("requested").asText());
    }

    @Test
    void missingClientCapabilitiesIs400InvalidParams() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", MODERN);
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"params\":" + params + ",\"id\":8}";
        Resp r = post(mcpUrl, body, modernHeaders("tools/list"));
        assertEquals(400, r.status);
        assertEquals(-32602, parse(r).get("error").get("code").asInt());
    }

    @Test
    void mcpMethodHeaderMismatchIs32020() throws Exception {
        Resp r = post(mcpUrl, modernRequest("tools/list", 9, null),
                hdr("MCP-Protocol-Version", MODERN),
                hdr("Mcp-Method", "tools/call"));
        assertEquals(400, r.status);
        JsonNode error = parse(r).get("error");
        assertEquals(-32020, error.get("code").asInt());
        assertTrue(error.get("data").asText().contains("Mcp-Method"),
                "Error message must name the affected header: " + error.get("data").asText());
    }

    @Test
    void protocolVersionHeaderMismatchIs32020() throws Exception {
        Resp r = post(mcpUrl, modernRequest("tools/list", 10, null),
                hdr("MCP-Protocol-Version", "2025-06-18"),
                hdr("Mcp-Method", "tools/list"));
        assertEquals(400, r.status);
        assertEquals(-32020, parse(r).get("error").get("code").asInt());
    }

    @Test
    void mcpNameMismatchIs32020() throws Exception {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("name", "@Echo");
        Resp r = post(mcpUrl, modernRequest("tools/call", 11, p), modernHeaders("tools/call", "@View"));
        assertEquals(400, r.status);
        assertEquals(-32020, parse(r).get("error").get("code").asInt());
    }

    @Test
    void mcpNameMissingIs32020() throws Exception {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("name", "@Echo");
        Resp r = post(mcpUrl, modernRequest("tools/call", 12, p), modernHeaders("tools/call"));
        assertEquals(400, r.status);
        assertEquals(-32020, parse(r).get("error").get("code").asInt());
    }

    @Test
    void mcpNameBase64SentinelWithNonAsciiIsDecoded() throws Exception {
        // Tool mit Nicht-ASCII-Name: Mcp-Name MUSS im Base64-Sentinel-Format
        // gesendet werden; die Servlet muss es dekodieren und vergleichen.
        AgentStub stub = new AgentStub();
        stub.setHandler((m, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("list".equals(op)) {
                return AgentStub.Response.ok("{\"status\":\"ok\",\"tools\":["
                        + "{\"name\":\"Ähnlich\",\"description\":\"Umlaut-Tool\"}]}");
            }
            if ("call".equals(op)) {
                return AgentStub.Response.ok("{\"status\":\"ok\",\"tool\":\"Ähnlich\"}");
            }
            return AgentStub.Response.ok("{\"status\":\"error\",\"code\":\"BAD_OP\"}");
        });
        Server s = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new DominoMcpServlet()), "/mcp/servlet");
        ctx.addServlet(new ServletHolder(stub), "/mcp_tools.nsf/McpGateway");
        s.setHandler(ctx);
        s.start();
        SERVERS.add(s);
        String url = s.getURI().resolve("/mcp/servlet").toString();

        String encodedName = "=?base64?"
                + Base64.getEncoder().encodeToString("Ähnlich".getBytes(StandardCharsets.UTF_8))
                + "?=";
        ObjectNode p = MAPPER.createObjectNode();
        p.put("name", "Ähnlich");
        p.set("arguments", MAPPER.createObjectNode());
        Resp r = post(url, modernRequest("tools/call", 13, p),
                hdr("MCP-Protocol-Version", MODERN),
                hdr("Mcp-Method", "tools/call"),
                hdr("Mcp-Name", encodedName));
        assertEquals(200, r.status,
                "Base64 sentinel in Mcp-Name must be decoded and compared with the body: "
                        + r.body);
        assertEquals(false, parse(r).get("result").get("isError").asBoolean());
    }

    @Test
    void unknownMethodModernIs404() throws Exception {
        Resp r = post(mcpUrl, modernRequest("foo/bar", 14, null), modernHeaders("foo/bar"));
        assertEquals(404, r.status);
        assertEquals(-32601, parse(r).get("error").get("code").asInt());
    }

    @Test
    void foreignOriginIs403() throws Exception {
        Resp r = rawPost(mcpUrl,
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":15}",
                "http://evil.example.com");
        assertEquals(403, r.status);
        assertTrue(parse(r).get("error").get("message").asText().contains("Origin"));
    }

    @Test
    void foreignOriginWithWrongPortIs403() throws Exception {
        Resp r = rawPost(mcpUrl,
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":15b}",
                "http://127.0.0.1:9999");
        assertEquals(403, r.status);
    }

    @Test
    void sameOriginIsAccepted() throws Exception {
        URL u = new URL(mcpUrl);
        String origin = u.getProtocol() + "://" + u.getHost() + ":" + u.getPort();
        Resp r = rawPost(mcpUrl,
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":16}",
                origin);
        assertEquals(200, r.status);
        assertEquals(3, parse(r).get("result").get("tools").size());
    }

    @Test
    void missingOriginIsAccepted() throws Exception {
        Resp r = rawPost(mcpUrl,
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":\"16b\"}",
                null);
        assertEquals(200, r.status);
        assertEquals(3, parse(r).get("result").get("tools").size());
    }

    @Test
    void deleteIs405() throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(mcpUrl).openConnection();
        con.setRequestMethod("DELETE");
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        assertEquals(405, con.getResponseCode());
    }

    @Test
    void modernNotificationStill202() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode meta = params.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", MODERN);
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\","
                + "\"params\":" + params + "}";
        Resp r = post(mcpUrl, body, modernHeaders("notifications/initialized"));
        assertEquals(202, r.status);
    }

    @Test
    void legacyRequestWithoutMetaStillWorks() throws Exception {
        // Kein _meta, keine Header → Legacy-Pfad mit unverändertem Verhalten.
        Resp r = post(mcpUrl, "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":17}");
        assertEquals(200, r.status);
        JsonNode result = parse(r).get("result");
        assertEquals(3, result.get("tools").size());
        assertFalse(result.has("resultType"), "Legacy result must not carry resultType");
        assertFalse(result.has("_meta"), "Legacy result must not carry _meta");
        assertFalse(result.has("ttlMs"));
    }
}
