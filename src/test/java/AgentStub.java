import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Test-Servlet: simuliert den Notes-Web-Agent ({@code mcp_tools.nsf/McpGateway})
 * für die Integrationstests.
 *
 * <p>Wird im Test-Jetty neben der {@link DominoMcpServlet} gemountet — exakt
 * wie im Produktivbetrieb, wo der Agent auf demselben Server liegt wie die
 * MCP-Servlet (Host/Port leitet die Servlet aus dem einkommenden Request ab,
 * keine externe Konfiguration).
 *
 * <p>Der getestete Client ruft den Stub per HTTP-POST mit einem JSON-Body an
 * (URL-Trigger {@code ?open}); der {@link Handler} erhält HTTP-Method +
 * geparster JSON-Body und liefert eine {@link Response}.
 * {@link #lastBody}/{@link #lastRawBody} dokumentieren den letzten Request
 * für Assertions.
 */
public class AgentStub extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Antwort des simulierten Agents. */
    public static class Response {
        final int status;
        final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public static Response ok(String body) {
            return new Response(200, body);
        }
    }

    /** Verhält sich wie der Agent: (HTTP-Method, JSON-Body) → Response. */
    public interface Handler {
        Response handle(String method, JsonNode body) throws Exception;
    }

    /** JSON-Body des letzten Requests, geparst (null, wenn leer/ungültig). */
    public volatile JsonNode lastBody;

    /** Roher JSON-Body des letzten Requests (String). */
    public volatile String lastRawBody;

    private volatile Handler handler;

    public void setHandler(Handler h) {
        this.handler = h;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        lastRawBody = readBody(req);
        JsonNode body = null;
        if (lastRawBody != null && !lastRawBody.trim().isEmpty()) {
            try {
                body = MAPPER.readTree(lastRawBody);
            } catch (IOException e) {
                body = null;
            }
        }
        lastBody = body;

        Response r;
        try {
            r = (handler != null)
                    ? handler.handle(req.getMethod(), body)
                    : new Response(500, "AgentStub: kein Handler gesetzt");
        } catch (Exception e) {
            r = new Response(500, "AgentStub: Handler-Fehler: " + e);
        }
        resp.setStatus(r.status);
        resp.setContentType("application/json; charset=utf-8");
        resp.getWriter().write(r.body);
    }

    private static String readBody(HttpServletRequest req) throws IOException {
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
}
