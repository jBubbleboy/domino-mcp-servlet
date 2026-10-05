import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-Tests für den HTTP-Client — echtes HTTP gegen den Agent-Stub
 * auf lokalem Jetty (siehe {@link AgentStubServer}).
 */
class NotesAgentClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentStubServer stubServer;
    private final NotesAgentClient client = new NotesAgentClient();

    @BeforeEach
    void setUp() throws Exception {
        stubServer = new AgentStubServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        stubServer.close();
    }

    @Test
    void fetchToolListSendsOpListAndReturnsBody() throws Exception {
        final String body = "{\"status\":\"ok\",\"tools\":[]}";
        stubServer.stub().setHandler((method, b) -> AgentStub.Response.ok(body));

        String result = client.fetchToolList(stubServer.agentBaseUrl(), 5000);

        assertEquals(body, result);
        assertEquals("list", stubServer.stub().lastBody.get("op").asText());
        assertEquals("{\"op\":\"list\"}", stubServer.stub().lastRawBody.trim());
    }

    @Test
    void callToolSendsToolAndParamsObject() throws Exception {
        final String body = "{\"status\":\"ok\"}";
        stubServer.stub().setHandler((method, b) -> AgentStub.Response.ok(body));

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("viewName", "(All Documents)");
        args.put("count", 25);

        String result = client.callTool(stubServer.agentBaseUrl(), 5000, "@View", args);

        assertEquals(body, result);
        assertEquals("call", stubServer.stub().lastBody.get("op").asText());
        assertEquals("@View", stubServer.stub().lastBody.get("tool").asText());
        JsonNode params = stubServer.stub().lastBody.get("params");
        assertEquals("(All Documents)", params.get("viewName").asText());
        assertEquals(25, params.get("count").asInt());
    }

    @Test
    void callToolWithoutArgumentsSendsEmptyObject() throws Exception {
        stubServer.stub().setHandler(
                (method, b) -> AgentStub.Response.ok("{\"status\":\"ok\"}"));

        client.callTool(stubServer.agentBaseUrl(), 5000, "@NoArgs", null);

        JsonNode params = stubServer.stub().lastBody.get("params");
        assertTrue(params.isObject(), "params must be a JSON object");
        assertTrue(params.isEmpty(), "empty arguments => empty object {}");
    }

    @Test
    void callToolSendsAgentBodyAsAsciiSafeUnicode() throws Exception {
        // Domino dekodiert den POST-Body mit der Server-Codepage (CP437/CP850),
        // NICHT mit dem deklarierten charset (fuer rohe JSON-Bodies). Deshalb
        // muss der Agent-Body ASCII-sicher sein: nicht-ASCII wird als Unicode-
        // Escape (backslash-u-XXXX, Jackson ESCAPE_NON_ASCII) gesendet ->
        // codepage-unabhaengig. Der Agent (dnWeb.unescapeJsonStr) dekodiert sie.
        stubServer.stub().setHandler(
                (method, b) -> AgentStub.Response.ok("{\"status\":\"ok\"}"));

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("task", "äöüß");

        client.callTool(stubServer.agentBaseUrl(), 5000, "@Write", args);

        String raw = stubServer.stub().lastRawBody;
        // 1) Der Body muss rein-ASCII sein (kein Zeichen > 0x7F), sonst dekodiert
        //    Domino ihn mit der falschen Codepage (Mojibake).
        for (int i = 0; i < raw.length(); i++) {
            assertTrue(raw.charAt(i) <= 0x7F,
                    "Agent body must be pure ASCII (codepage-safe); found char "
                            + (int) raw.charAt(i) + " in: " + raw);
        }
        // 2) Die (escaped) Werte muessen sich auf die Original-Werte
        //    zurueckfuhren lassen (Jackson dekodiert die Escapes beim Parsen).
        assertEquals("äöüß",
                stubServer.stub().lastBody.get("params").get("task").asText(),
                "escaped umlauts must round-trip to the original value");
    }

    @Test
    void non200IsNotesAgentException() throws Exception {
        stubServer.stub().setHandler(
                (method, b) -> new AgentStub.Response(500, "boom"));

        NotesAgentException e = assertThrows(NotesAgentException.class,
                () -> client.fetchToolList(stubServer.agentBaseUrl(), 5000));
        assertTrue(e.getMessage().contains("500"),
                "Message must contain the HTTP status: " + e.getMessage());
    }

    @Test
    void timeoutIsNotesAgentException() throws Exception {
        // Der Agent antwortet erst nach 2,5 s; Timeout ist 1 s.
        stubServer.stub().setHandler((method, b) -> {
            Thread.sleep(2500);
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });

        long start = System.currentTimeMillis();
        NotesAgentException e = assertThrows(NotesAgentException.class,
                () -> client.fetchToolList(stubServer.agentBaseUrl(), 1000));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(e.getMessage().contains("Timeout"), "Message: " + e.getMessage());
        assertTrue(elapsed < 2500,
                "must abort before the agent responds (after " + elapsed + " ms)");
    }

    @Test
    void refusedConnectionIsNotesAgentException() throws Exception {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        String deadUrl = "http://127.0.0.1:" + deadPort + "/mcp_tools.nsf/McpGateway";

        NotesAgentException e = assertThrows(NotesAgentException.class,
                () -> client.fetchToolList(deadUrl, 1000));
        assertTrue(e.getMessage().contains("unreachable"),
                "Message: " + e.getMessage());
    }

    // ---------- Per-User-Domino-Identität: Authorization-Weiterleitung ----------

    @Test
    void clientWithAuthorizationHeaderForwardsItToAgent() throws Exception {
        final String body = "{\"status\":\"ok\",\"tools\":[]}";
        final String auth = "Basic dXNlcjpwYXNz";
        stubServer.stub().setHandler((method, b) -> AgentStub.Response.ok(body));

        NotesAgentClient authClient = new NotesAgentClient(auth);
        String result = authClient.fetchToolList(stubServer.agentBaseUrl(), 5000);

        assertEquals(body, result);
        assertEquals(auth, stubServer.stub().lastAuthorization,
                "The Authorization header must be forwarded to the agent 1:1");
    }

    @Test
    void clientWithoutAuthorizationHeaderSendsNone() throws Exception {
        stubServer.stub().setHandler(
                (method, b) -> AgentStub.Response.ok("{\"status\":\"ok\"}"));

        client.callTool(stubServer.agentBaseUrl(), 5000, "@NoArgs", null);

        assertEquals(null, stubServer.stub().lastAuthorization,
                "Without a set header, no Authorization header may be sent");
    }

    @Test
    void everyOperationForwardsAuthorizationHeader() throws Exception {
        final String auth = "Basic dXNlcjpwYXNz";
        stubServer.stub().setHandler(
                (method, b) -> AgentStub.Response.ok("{\"status\":\"ok\"}"));

        NotesAgentClient authClient = new NotesAgentClient(auth);
        String base = stubServer.agentBaseUrl();
        authClient.fetchToolList(base, 5000);
        assertEquals(auth, stubServer.stub().lastAuthorization);
        authClient.callTool(base, 5000, "@View", null);
        assertEquals(auth, stubServer.stub().lastAuthorization);
        authClient.fetchResourceList(base, 5000);
        assertEquals(auth, stubServer.stub().lastAuthorization);
        authClient.readResource(base, 5000, "domino://x/(All)");
        assertEquals(auth, stubServer.stub().lastAuthorization);
        authClient.fetchPromptList(base, 5000);
        assertEquals(auth, stubServer.stub().lastAuthorization);
        authClient.getPrompt(base, 5000, "summarize", null);
        assertEquals(auth, stubServer.stub().lastAuthorization);
    }
}
