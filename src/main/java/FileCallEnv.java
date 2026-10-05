import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Cache für die Agent-Konfiguration ({@code op=env}): Verzeichnis für das
 * Datei-Handover, das Agent-Payload-Limit und die Servlet-Konfiguration
 * (Agent-Timeout + maximale LLM-Body-Größe aus der vConfig).
 *
 * <p>Single Source of Truth ist der Agent: Das Verzeichnis wird auf der
 * Agent-Seite aus der vConfig abgeleitet (keine manuelle
 * Pfad-Konfiguration in der Servlet), das Handover-Limit ist eine
 * Agent-Konstante, der Agent-Timeout und die maximale LLM-Body-Größe
 * kommen aus den vConfig-Feldern AGENT_TIMEOUT_MS / MAX_PAYLOAD_BYTES
 * (Präzedenz vConfig &gt; web.xml &gt; eingebauter Default; Werte 1:1
 * ohne Plausibilitäts-Check). Die Servlet fragt den Agent ab und merkt
 * sich das Ergebnis für die übergebene TTL (Default 60 s, konsistent mit
 * den anderen Registry-Caches). Fehlen optionale Felder (älterer Agent)
 * oder ist der Agent nicht erreichbar, gelten die Servlet-eigenen
 * web.xml-Werte bzw. Defaults (Fail-Open).</p>
 */
public class FileCallEnv {

    /** Agent-Konfiguration für das Datei-Handover und die Servlet. */
    public static class Env {
        /** Absoluter Pfad des Handover-Verzeichnisses (endet mit Separator). */
        public final String fileCallDir;
        /** Maximale Handover-Dateigröße in Bytes (Agent-Konstante). */
        public final long maxFileCallBytes;
        /** Connect/Read-Timeout je Agent-Call in ms (0 = nicht vom Agent gesetzt). */
        public final long agentTimeoutMs;
        /** Maximale LLM-Body-Größe in Bytes (0 = nicht vom Agent gesetzt). */
        public final long maxPayloadBytes;
        /** Server-Identität (vConfig SERVER_NAME, Default "Domino"); leer =
         *  nicht vom Agent gesetzt (Servlet behält web.xml/Default). */
        public final String serverName;

        Env(String fileCallDir, long maxFileCallBytes,
            long agentTimeoutMs, long maxPayloadBytes, String serverName) {
            this.fileCallDir = fileCallDir;
            this.maxFileCallBytes = maxFileCallBytes;
            this.agentTimeoutMs = agentTimeoutMs;
            this.maxPayloadBytes = maxPayloadBytes;
            this.serverName = serverName;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile Env cached;
    private static volatile long cachedAtMs;

    /**
     * Liefert die Agent-Konfiguration; ruft den Agent nur auf, wenn der
     * Cache leer oder abgelaufen ist.
     *
     * @param client        Agent-Client
     * @param agentBaseUrl  Basis-URL des Agents ohne Query-String
     * @param timeoutMs     Connect/Read-Timeout in Millisekunden
     * @param ttlMs         Gültigkeitsdauer des Caches in Millisekunden
     * @throws IOException Agent nicht erreichbar oder Antwort nicht parsebar
     */
    public static Env get(NotesAgentClient client, String agentBaseUrl,
                          int timeoutMs, long ttlMs) throws IOException {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAtMs) < ttlMs) {
            return cached;
        }
        Env fresh = parse(client.fetchEnv(agentBaseUrl, timeoutMs));
        cached = fresh;
        cachedAtMs = System.currentTimeMillis();
        return fresh;
    }

    /** Cache verwerfen (z. B. für Tests oder nach Konfigurationsänderungen). */
    public static void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    /**
     * Parst die Agent-Antwort auf {@code op=env}
     * ({@code {"status":"ok","env":{"fileCallDir":"...","maxFileCallBytes":N,
     * "agentTimeoutMs":M,"maxPayloadBytes":P}}}). Die Servlet-Konfiguration
     * ({@code agentTimeoutMs}/{@code maxPayloadBytes}) ist optional:
     * fehlt das Feld oder ist es nicht-numerisch/≤0, so wird 0 geliefert
     * (Servlet behält web.xml/Default).
     *
     * @throws IOException Antwort enthält keine gültige Env-Konfiguration
     */
    static Env parse(String agentJson) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(agentJson);
        } catch (IOException e) {
            throw new IOException("Agent env response is not valid JSON: "
                    + (agentJson == null ? "<null>" : agentJson.substring(0, Math.min(300, agentJson.length()))), e);
        }
        if (root == null || !root.isObject() || root.get("env") == null
                || !root.get("env").isObject()) {
            throw new IOException("Agent did not return an env object "
                    + "(expected: {\"status\":\"ok\",\"env\":{...}})");
        }
        JsonNode env = root.get("env");
        JsonNode dir = env.get("fileCallDir");
        if (dir == null || !dir.isTextual() || dir.asText().trim().isEmpty()) {
            throw new IOException("Agent env is missing a non-empty fileCallDir");
        }
        JsonNode max = env.get("maxFileCallBytes");
        if (max == null || !max.isNumber() || max.asLong() <= 0) {
            throw new IOException("Agent env is missing a positive numeric maxFileCallBytes");
        }
        return new Env(dir.asText().trim(), max.asLong(),
                optionalPositiveLong(env, "agentTimeoutMs"),
                optionalPositiveLong(env, "maxPayloadBytes"),
                optionalText(env, "serverName"));
    }

    /**
     * Optionales Env-Number-Feld: liefert den Wert, wenn er numerisch
     * und &gt; 0 ist, sonst 0 (nicht vom Agent gesetzt).
     */
    private static long optionalPositiveLong(JsonNode env, String name) {
        JsonNode node = env.get(name);
        if (node == null || !node.isNumber()) {
            return 0L;
        }
        long value = node.asLong();
        return value > 0 ? value : 0L;
    }

    /**
     * Optionales Env-Textfeld: liefert den (getrimmten) Wert, wenn er
     * textuell ist, sonst "" (nicht vom Agent gesetzt).
     */
    private static String optionalText(JsonNode env, String name) {
        JsonNode node = env.get(name);
        if (node == null || !node.isTextual()) {
            return "";
        }
        return node.asText().trim();
    }
}
