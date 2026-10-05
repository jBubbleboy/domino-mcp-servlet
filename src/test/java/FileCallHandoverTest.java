import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-Tests für das Datei-Handover (op=filecall): Payloads über
 * {@code MAX_AGENT_BODY_INLINE} (32 000 B) wandern per temporärer Datei zum
 * Agent statt über den korrupten {@code Request_Content}-Pfad. Der Agent wird
 * per Stub simuliert ({@link AgentStubServer}); das Env-Verzeichnis ist eine
 * echte Temp-Dir, die Datei-Interaktion ist echte Datei-I/O.
 */
class FileCallHandoverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentStubServer stubServer;
    private final NotesAgentClient client = new NotesAgentClient();

    @BeforeEach
    void setUp() throws Exception {
        stubServer = new AgentStubServer();
        FileCallEnv.invalidate();
    }

    @AfterEach
    void tearDown() throws Exception {
        stubServer.close();
        FileCallEnv.invalidate();
    }

    /** Baut ein Env-JSON mit einem echten Verzeichnis-Pfad (safe escaping). */
    private static String envJson(String fileCallDir, long maxFileCallBytes) throws IOException {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("fileCallDir", fileCallDir);
        env.put("maxFileCallBytes", maxFileCallBytes);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "ok");
        root.set("env", env);
        return MAPPER.writeValueAsString(root);
    }

    private static long checksum(byte[] data) {
        long sum = 0;
        for (byte b : data) {
            sum = (sum + (b & 0xFF)) % 0x7FFFFFFF;
        }
        return sum;
    }

    /** Argumente, die einen Agent-Body über 32 000 B erzeugen. */
    private static Map<String, Object> bigArgs(int contentSize) {
        StringBuilder sb = new StringBuilder(contentSize);
        for (int i = 0; i < contentSize; i++) {
            sb.append('A');
        }
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("in_content", sb.toString());
        return args;
    }

    @Test
    void fetchEnvReturnsEnvJson() throws Exception {
        File dir = Files.createTempDirectory("filecall-env").toFile();
        stubServer.stub().setHandler((method, body) ->
                AgentStub.Response.ok(envJson(dir.getAbsolutePath() + File.separator, 10239999L)));

        String result = client.fetchEnv(stubServer.agentBaseUrl(), 5000);

        assertEquals("env", stubServer.stub().lastBody.get("op").asText());
        FileCallEnv.Env env = FileCallEnv.parse(result);
        assertEquals(dir.getAbsolutePath() + File.separator, env.fileCallDir);
        assertEquals(10239999L, env.maxFileCallBytes);
    }

    @Test
    void fetchEnvWithAgentErrorThrowsNotesAgentException() {
        stubServer.stub().setHandler((method, body) ->
                new AgentStub.Response(500, "{\"status\":\"error\",\"code\":\"ENV_DOWN\"}"));

        NotesAgentException e = assertThrows(NotesAgentException.class,
                () -> client.fetchEnv(stubServer.agentBaseUrl(), 5000));
        assertEquals(500, e.getHttpStatus());
    }

    @Test
    void envParseOptionalServletConfigFields() throws Exception {
        File dir = Files.createTempDirectory("filecall-env2").toFile();
        ObjectNode root = (ObjectNode) MAPPER.readTree(
                envJson(dir.getAbsolutePath() + File.separator, 10239999L));

        // Älterer Agent (ohne neue Felder): 0 → Servlet behält web.xml/Default.
        FileCallEnv.Env legacy = FileCallEnv.parse(MAPPER.writeValueAsString(root));
        assertEquals(0L, legacy.agentTimeoutMs);
        assertEquals(0L, legacy.maxPayloadBytes);

        // Neue Felder: 1:1 übernehmen (kein Clamp).
        ObjectNode env = (ObjectNode) root.get("env");
        env.put("agentTimeoutMs", 60000);
        env.put("maxPayloadBytes", 1000000);
        FileCallEnv.Env fresh = FileCallEnv.parse(MAPPER.writeValueAsString(root));
        assertEquals(60000L, fresh.agentTimeoutMs);
        assertEquals(1000000L, fresh.maxPayloadBytes);

        // Nicht-numerisch bzw. ≤ 0 => 0 (nicht gesetzt).
        env.put("agentTimeoutMs", "abc");
        env.put("maxPayloadBytes", 0);
        FileCallEnv.Env invalid = FileCallEnv.parse(MAPPER.writeValueAsString(root));
        assertEquals(0L, invalid.agentTimeoutMs);
        assertEquals(0L, invalid.maxPayloadBytes);
    }

    @Test
    void envParseRejectsInvalidResponses() throws Exception {
        assertThrows(IOException.class,
                () -> FileCallEnv.parse("{\"status\":\"ok\"}"));
        assertThrows(IOException.class,
                () -> FileCallEnv.parse("{\"status\":\"ok\",\"env\":{}}"));
        assertThrows(IOException.class,
                () -> FileCallEnv.parse("{\"status\":\"ok\",\"env\":{\"fileCallDir\":\"\",\"maxFileCallBytes\":1}}"));
        assertThrows(IOException.class,
                () -> FileCallEnv.parse("{\"status\":\"ok\",\"env\":{\"fileCallDir\":\"/x\",\"maxFileCallBytes\":0}}"));
        assertThrows(IOException.class,
                () -> FileCallEnv.parse("kein-json"));
    }

    @Test
    void bigPayloadUsesFileHandoverAndFileIsDeleted() throws Exception {
        File dir = Files.createTempDirectory("filecall").toFile();
        AtomicInteger envQueries = new AtomicInteger();
        final AtomicReference<byte[]> fileContentAtCall = new AtomicReference<byte[]>();
        final AtomicReference<JsonNode> envelope = new AtomicReference<JsonNode>();
        final String payloadMarker = "FILECALL-ROUNDTRIP-OK";

        stubServer.stub().setHandler((method, body) -> {
            String op = body.get("op").asText();
            if ("env".equals(op)) {
                envQueries.incrementAndGet();
                return AgentStub.Response.ok(envJson(dir.getAbsolutePath() + File.separator, 10_000_000L));
            }
            if ("filecall".equals(op)) {
                envelope.set(body);
                File f = new File(dir, body.get("file").asText());
                fileContentAtCall.set(Files.readAllBytes(f.toPath()));
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"tool\":\"@Big\",\"marker\":\"" + payloadMarker + "\"}");
            }
            return AgentStub.Response.ok(
                    "{\"status\":\"error\",\"code\":\"UNEXPECTED_OP\",\"op\":\"" + op + "\"}");
        });

        Map<String, Object> args = bigArgs(100_000);
        String result = client.callTool(stubServer.agentBaseUrl(), 5000, "@Big", args);

        // Agent-Antwort (aus dem Stub) kommt zurück.
        assertEquals(payloadMarker, MAPPER.readTree(result).get("marker").asText());
        // Der Agent erhielt das Envelope (klein), nicht den 100-KB-Roh-Body.
        assertEquals("filecall", stubServer.stub().lastBody.get("op").asText());
        assertTrue(stubServer.stub().lastRawBody.length() < 1000,
                "Agent-Body muss ein kleines Envelope sein, war: " + stubServer.stub().lastRawBody.length());

        // Die Datei enthielt während des Calls den Original-Payload.
        byte[] content = fileContentAtCall.get();
        assertTrue(content.length > 32_000, "Payload über Handover-Schwelle: " + content.length);
        String payloadText = new String(content, StandardCharsets.UTF_8);
        assertTrue(payloadText.contains("\"op\":\"call\""), "Datei-Inhalt ist der Agent-Body (op=call)");
        assertTrue(payloadText.contains("\"tool\":\"@Big\""));
        assertTrue(payloadText.length() > 100_000, "Content-Parameter ist intakt: " + payloadText.length());

        // Envelope: size + checkSum stimmen mit dem Datei-Inhalt überein.
        JsonNode env = envelope.get();
        assertEquals((long) content.length, env.get("size").asLong());
        assertEquals(checksum(content), env.get("checkSum").asLong());
        assertTrue(env.get("file").asText().endsWith(".json"));

        // Envelope: path = absoluter Pfad der Handover-Datei (der Agent
        // bevorzugt ihn und validiert ihn per fileCallDir-Praefix-Check).
        String pathField = env.get("path").asText();
        assertTrue(pathField.startsWith(dir.getAbsolutePath()),
                "path liegt im Env-Verzeichnis: " + pathField);
        assertEquals(new File(dir, env.get("file").asText()).getAbsolutePath(), pathField,
                "path == absolutem fileCallDir + file");

        // Cleanup: nach dem Call ist das Verzeichnis leer (Servlet-finally).
        File[] remaining = dir.listFiles();
        assertEquals(0, remaining == null ? 0 : remaining.length, "Handover-Datei muss gelöscht sein");
        assertEquals(1, envQueries.get(), "Env-Query exakt einmal (lazy, vor dem Handover)");
    }

    @Test
    void envCacheIsReusedWithinTtl() throws Exception {
        File dir = Files.createTempDirectory("filecall-cache").toFile();
        AtomicInteger envQueries = new AtomicInteger();
        stubServer.stub().setHandler((method, body) -> {
            String op = body.get("op").asText();
            if ("env".equals(op)) {
                envQueries.incrementAndGet();
                return AgentStub.Response.ok(envJson(dir.getAbsolutePath() + File.separator, 10_000_000L));
            }
            if ("filecall".equals(op)) {
                File f = new File(dir, body.get("file").asText());
                assertTrue(Files.readAllBytes(f.toPath()).length > 32_000);
                return AgentStub.Response.ok("{\"status\":\"ok\"}");
            }
            return AgentStub.Response.ok("{\"status\":\"error\",\"code\":\"UNEXPECTED_OP\"}");
        });

        client.callTool(stubServer.agentBaseUrl(), 5000, "@Big", bigArgs(40_000));
        client.callTool(stubServer.agentBaseUrl(), 5000, "@Big", bigArgs(40_000));

        assertEquals(1, envQueries.get(), "Zweiter Handover nutzt den Env-Cache (60 s TTL)");
        assertEquals(0, dir.listFiles().length, "Beide Handover-Dateien gelöscht");
    }

    @Test
    void smallPayloadStaysInlineWithoutEnvQuery() throws Exception {
        AtomicInteger envQueries = new AtomicInteger();
        stubServer.stub().setHandler((method, body) -> {
            String op = body.get("op").asText();
            if ("env".equals(op)) {
                envQueries.incrementAndGet();
                return AgentStub.Response.ok(envJson("/tmp/never", 100L));
            }
            return AgentStub.Response.ok("{\"status\":\"ok\"}");
        });

        client.fetchToolList(stubServer.agentBaseUrl(), 5000);
        client.callTool(stubServer.agentBaseUrl(), 5000, "@View",
                new HashMap<String, Object>());

        assertEquals("call", stubServer.stub().lastBody.get("op").asText());
        assertEquals(0, envQueries.get(), "Inline-Pfad fragt kein Env ab");
    }

    @Test
    void payloadAboveAgentLimitThrows413() throws Exception {
        File dir = Files.createTempDirectory("filecall-limit").toFile();
        stubServer.stub().setHandler((method, body) ->
                AgentStub.Response.ok(envJson(dir.getAbsolutePath() + File.separator, 5_000L)));

        // 40 KB > MAX_AGENT_BODY_INLINE (32 000) → Handover-Pfad, dort greift
        // die Env-Limit-Prüfung (5 000 B) vor dem Datei-Schreibzugriff.
        NotesAgentException e = assertThrows(NotesAgentException.class,
                () -> client.callTool(stubServer.agentBaseUrl(), 5000, "@Big", bigArgs(40_000)));
        assertEquals(413, e.getHttpStatus());
        assertTrue(e.getMessage().contains("exceeds agent filecall limit"));
        assertEquals(0, dir.listFiles().length, "Keine Datei bei Limit-Verstoß (Check vor Schreibzugriff)");
    }

    @Test
    void handoverFailsCleanlyWhenEnvQueryFails() throws Exception {
        // Agent kennt op=env nicht (alter Agent): sauberer Fehler statt Crash.
        stubServer.stub().setHandler((method, body) ->
                AgentStub.Response.ok(
                        "{\"status\":\"error\",\"code\":\"BAD_OP\",\"message\":\"unbekannte op\"}"));

        NotesAgentException e = assertThrows(NotesAgentException.class,
                () -> client.callTool(stubServer.agentBaseUrl(), 5000, "@Big", bigArgs(40_000)));
        assertEquals(0, e.getHttpStatus());
        assertTrue(e.getMessage().contains("FileCall env query failed"));
    }

    @Test
    void fileCallEnvGetCachesParsedEnv() throws Exception {
        File dir = Files.createTempDirectory("filecall-get").toFile();
        AtomicInteger envQueries = new AtomicInteger();
        stubServer.stub().setHandler((method, body) -> {
            if ("env".equals(body.get("op").asText())) {
                envQueries.incrementAndGet();
            }
            return AgentStub.Response.ok(envJson(dir.getAbsolutePath() + File.separator, 12345L));
        });

        FileCallEnv.Env first = FileCallEnv.get(client, stubServer.agentBaseUrl(), 5000, 60_000L);
        FileCallEnv.Env second = FileCallEnv.get(client, stubServer.agentBaseUrl(), 5000, 60_000L);

        assertEquals(dir.getAbsolutePath() + File.separator, first.fileCallDir);
        assertEquals(12345L, second.maxFileCallBytes);
        assertTrue(first == second, "Innerhalb der TTL: gleicher Cache-Eintrag");
        assertEquals(1, envQueries.get());
    }

    @Test
    void fileCallEnvGetDoesNotCacheInvalidResponses() throws Exception {
        stubServer.stub().setHandler((method, body) ->
                AgentStub.Response.ok("{\"status\":\"ok\",\"env\":{}}"));

        assertThrows(IOException.class,
                () -> FileCallEnv.get(client, stubServer.agentBaseUrl(), 5000, 60_000L));
        // Nach einem Fehler darf der nächste Call erneut fragen (nichts gecacht).
        assertThrows(IOException.class,
                () -> FileCallEnv.get(client, stubServer.agentBaseUrl(), 5000, 60_000L));
    }
}
