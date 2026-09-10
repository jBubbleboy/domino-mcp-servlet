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
        assertTrue(params.isObject(), "params muss ein JSON-Objekt sein");
        assertTrue(params.isEmpty(), "leere Argumente => leeres Objekt {}");
    }

    @Test
    void non200IsNotesAgentException() throws Exception {
        stubServer.stub().setHandler(
                (method, b) -> new AgentStub.Response(500, "boom"));

        NotesAgentException e = assertThrows(NotesAgentException.class,
                () -> client.fetchToolList(stubServer.agentBaseUrl(), 5000));
        assertTrue(e.getMessage().contains("500"),
                "Message muss den HTTP-Status enthalten: " + e.getMessage());
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
                "muss abbrechen, bevor der Agent antwortet (nach " + elapsed + " ms)");
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
        assertTrue(e.getMessage().contains("nicht erreichbar"),
                "Message: " + e.getMessage());
    }
}
