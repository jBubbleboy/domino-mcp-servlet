import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Body-Größen-Guardrails der Servlet (readBody):
 * <ul>
 *   <li>declared (Content-Length) != received → -32602 „body corrupt"
 *       (Replay-/Datenverlust-Zone des Domino-HTTP-Tasks)</li>
 *   <li>received &gt; mcpMaxPayloadBytes → -32602 „Payload too large"
 *       (Fast-Fail statt rohem 500-HTML des HTTP-Tasks)</li>
 * </ul>
 */
class BodyGuardrailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<Server> servers = new ArrayList<Server>();

    /** Startet Jetty mit der Servlet (optionales mcpMaxPayloadBytes via Init-Param). */
    private String startServlet(long maxPayloadBytes) throws Exception {
        Server s = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ServletHolder holder = new ServletHolder(new DominoMcpServlet());
        if (maxPayloadBytes > 0) {
            holder.setInitParameter("mcpMaxPayloadBytes", String.valueOf(maxPayloadBytes));
        }
        ctx.addServlet(holder, "/mcp/servlet");
        s.setHandler(ctx);
        s.start();
        servers.add(s);
        return s.getURI().resolve("/mcp/servlet").toString();
    }

    private JsonNode postAndParse(String url, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = con.getResponseCode();
        InputStream in = status >= 400 ? con.getErrorStream() : con.getInputStream();
        String resp = in == null ? "" : readAll(in);
        con.disconnect();
        return MAPPER.readTree(resp);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        while ((n = in.read(block)) != -1) {
            buf.write(block, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void sizeMismatchIsRejectedAsCorrupt() throws Exception {
        // Raw-Socket-Request: Content-Length behauptet 1000 Bytes, gesendet
        // werden nur 100, dann Half-Close (EOF) → der Servlet empfängt
        // declared(1000) != received(100) und muss den Body abweisen.
        String url = startServlet(0);
        URL u = new URL(url);
        try (Socket sock = new Socket(u.getHost(), u.getPort())) {
            OutputStream out = sock.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append("POST /mcp/servlet HTTP/1.1\r\n");
            req.append("Host: ").append(u.getHost()).append(':').append(u.getPort()).append("\r\n");
            req.append("Content-Type: application/json; charset=utf-8\r\n");
            // Kompletter Body: ~570 Bytes (Padding). Content-Length deklariert
            // die volle Länge; gesendet werden nur die ersten 100 Bytes, dann
            // Half-Close (EOF) → der Servlet empfängt 100 < declared.
            StringBuilder full = new StringBuilder(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{\"pad\":\"");
            while (full.length() < 570) {
                full.append('x');
            }
            full.append("\"},\"id\":1}");
            req.append("Content-Length: ").append(full.length()).append("\r\n");
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(full.substring(0, 100).getBytes(StandardCharsets.UTF_8));
            out.flush();
            sock.shutdownOutput();

            String resp = readAll(sock.getInputStream());
            assertTrue(resp.startsWith("HTTP/1.1 400"), "HTTP 400 erwartet, war: "
                    + resp.substring(0, Math.min(40, resp.length())));
            JsonNode root = MAPPER.readTree(resp.split("\r\n\r\n", 2)[1]);
            JsonNode err = root.get("error");
            assertEquals(-32602, err.get("code").asInt());
            assertTrue(err.get("data").asText().contains("body corrupt"), err.get("data").asText());
        }
    }

    @Test
    void bodyAboveMaxPayloadBytesIsRejected() throws Exception {
        String url = startServlet(1000);
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{\"pad\":\"");
        while (sb.length() < 1900) {
            sb.append('x');
        }
        sb.append("\"},\"id\":1}");

        JsonNode root = postAndParse(url, sb.toString());
        assertEquals(-32602, root.get("error").get("code").asInt());
        assertTrue(root.get("error").get("data").asText().contains("Payload too large"),
                root.get("error").get("data").asText());
    }

    @Test
    void bodyBelowMaxPayloadBytesPasses() throws Exception {
        String url = startServlet(1000);
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\"},\"id\":1}";

        JsonNode root = postAndParse(url, body);
        assertTrue(root.has("result"), "Initialisierung muss ohne Guardrail-Fehler durchkommen: "
                + root.toString());
        assertEquals(1, root.get("id").asInt());
    }

    @Test
    void defaultMaxPayloadBytesIsTenMb() {
        // Ohne Init-Param gilt der Default aus dem Messbericht.
        assertEquals(10_239_999L, DominoMcpServlet.MAX_PAYLOAD_BYTES_DEFAULT);
    }

    @Test
    void envMaxPayloadBytesOverridesGuardrail() throws Exception {
        // vConfig (per op=env) senkt die Guardrail auf 1000 B: Ab dem
        // zweiten Request (Env-Refresh liegt im 60-s-Cache) wird ein
        // 1900-B-Body mit sauberem -32602 abgewiesen (1:1, kein Clamp).
        DominoMcpServlet servlet = new DominoMcpServlet();
        AgentStub stub = new AgentStub();
        stub.setHandler((method, body) -> {
            String op = (body != null && body.get("op") != null) ? body.get("op").asText() : "";
            if ("env".equals(op)) {
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"env\":{\"fileCallDir\":\"/tmp/bg/\",\"maxFileCallBytes\":10239999,"
                        + "\"maxPayloadBytes\":1000}}");
            }
            if ("list".equals(op)) {
                return AgentStub.Response.ok("{\"status\":\"ok\",\"tools\":[]}");
            }
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });
        Server s = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(servlet), "/mcp/servlet");
        ctx.addServlet(new ServletHolder(stub), "/mcp_tools.nsf/McpGateway");
        s.setHandler(ctx);
        s.start();
        servers.add(s);
        String url = s.getURI().resolve("/mcp/servlet").toString();
        FileCallEnv.invalidate();
        try {
            // Erster Request (klein): triggert den Env-Refresh.
            JsonNode first = postAndParse(url,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
            assertTrue(first.has("result"), "Erster Request (env-Refresh) muss ok sein: " + first);
            assertEquals(1000L, servlet.maxPayloadBytes(),
                    "vConfig-Wert gilt ab jetzt (1:1 uebernommen)");

            // Zweiter Request (1900 B > 1000 B): sauberes -32602.
            StringBuilder sb = new StringBuilder(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"params\":{\"pad\":\"");
            while (sb.length() < 1900) {
                sb.append('x');
            }
            sb.append("\"},\"id\":2}");
            JsonNode second = postAndParse(url, sb.toString());
            assertEquals(-32602, second.get("error").get("code").asInt());
            assertTrue(second.get("error").get("data").asText().contains("Payload too large"),
                    second.get("error").get("data").asText());
        } finally {
            FileCallEnv.invalidate();
        }
    }
}
