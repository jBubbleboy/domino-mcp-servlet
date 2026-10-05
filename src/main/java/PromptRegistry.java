import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cache für die Prompt-Liste ({@code prompts/list}), die die Notes-Datenbank
 * bereitstellt.
 *
 * <p>Spiegelbild von {@link ToolRegistry}: Die Servlet fragt die Prompts über
 * den Agent ({@code op=promptlist}) ab und merkt sich das Ergebnis für die
 * übergebene TTL (Default der Servlet: 60 s). Zusätzlich hält die Registry
 * die Prompt-Namen, um {@code prompts/get} vor dem Agent-Aufruf zu validieren
 * (unbekannter Name → {@code -32602}).
 *
 * <p>Das Parsing ist bewusst tolerant: Der Agent darf entweder
 * {@code {"status":"ok","prompts":[...]}} oder direkt ein JSON-Array liefern.
 */
public class PromptRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile ArrayNode cached;
    private volatile List<String> names;
    private volatile long cachedAtMs;

    /**
     * Liefert die Prompt-Liste (als {@link ArrayNode}); ruft den Agent nur
     * auf, wenn der Cache leer oder abgelaufen ist.
     *
     * @param client       Agent-Client
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect/Read-Timeout in Millisekunden
     * @param ttlMs        Gültigkeitsdauer des Caches in Millisekunden
     * @throws IOException Agent nicht erreichbar oder Antwort nicht parsebar
     */
    public ArrayNode getPrompts(NotesAgentClient client, String agentBaseUrl,
                                int timeoutMs, long ttlMs) throws IOException {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAtMs) < ttlMs) {
            return cached;
        }
        ArrayNode fresh = parse(client.fetchPromptList(agentBaseUrl, timeoutMs));
        names = collectNames(fresh);
        cached = fresh;
        cachedAtMs = System.currentTimeMillis();
        return fresh;
    }

    /**
     * Liefert den aktuell gemerkten Cache (null, wenn noch kein Cache
     * existiert). Dient als Fallback, wenn der Agent gerade nicht erreichbar ist.
     */
    public ArrayNode getCached() {
        return cached;
    }

    /**
     * true, wenn der Name in der (gecachten) Prompt-Liste enthalten ist.
     * Führt zu false, wenn noch kein Cache existiert.
     */
    public boolean hasName(String name) {
        return names != null && names.contains(name);
    }

    /** Cache verwerfen. */
    public void invalidate() {
        cached = null;
        names = null;
        cachedAtMs = 0L;
    }

    /**
     * Parst die Agent-Antwort auf {@code op=promptlist} (prompts-Array).
     *
     * @throws IOException Antwort enthält keine Prompt-Liste
     */
    static ArrayNode parse(String agentJson) throws IOException {
        JsonNode root = MAPPER.readTree(agentJson);
        JsonNode arr;
        if (root != null && root.isArray()) {
            arr = root;
        } else if (root != null && root.isObject() && root.get("prompts") != null
                && root.get("prompts").isArray()) {
            arr = root.get("prompts");
        } else {
            throw new IOException("Agent did not return a prompt list "
                    + "(expected: {\"status\":\"ok\",\"prompts\":[...]} or [...])");
        }
        return (ArrayNode) arr;
    }

    /** Sammelt die Namen der Prompt-Einträge (für die Get-Validierung). */
    private static List<String> collectNames(ArrayNode arr) {
        List<String> out = new ArrayList<String>();
        for (JsonNode p : arr) {
            if (p == null || !p.isObject()) {
                continue;
            }
            JsonNode name = p.get("name");
            if (name != null && name.isTextual() && !name.asText().trim().isEmpty()) {
                out.add(name.asText().trim());
            }
        }
        return Collections.unmodifiableList(out);
    }
}
