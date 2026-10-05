import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache für die Ressourcen-Liste ({@code resources/list}), die die
 * Notes-Datenbank bereitstellt.
 *
 * <p>Spiegelbild von {@link ToolRegistry}: Die Servlet fragt die Ressourcen
 * über den Agent ({@code op=reslist}) ab und merkt sich das Ergebnis für die
 * übergebene TTL (Default der Servlet: 60 s). Zusätzlich hält die Registry
 * die Ressourcen-URIs, um {@code resources/read} vor dem Agent-Aufruf
 * zu validieren (unbekannte URI → {@code -32602}).
 *
 * <p>Das Parsing ist bewusst tolerant: Der Agent darf entweder
 * {@code {"status":"ok","resources":[...]}} oder direkt ein JSON-Array
 * liefern.
 *
 * <p><b>Resource-Links (v2.3.0):</b> Zusätzlich zu den gelisteten URIs
 * merkt sich die Registry URIs, die in {@code resources/read}-Antworten als
 * {@code resource_link}-Content-Item referenziert wurden
 * ({@link #addLinkedUris(JsonNode, long)}). Damit können Hub-Ressourcen
 * ausgeblendete Ressourcen (nicht in {@code resources/list}) freischalten:
 * Die Servlet akzeptiert {@code resources/read} auch für solche URIs,
 * solange die Referenz jünger als die TTL ist. {@link #invalidate()}
 * leert beide Bereiche.
 */
public class ResourceRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile ArrayNode cached;
    private volatile List<String> uris;
    private volatile long cachedAtMs;

    /**
     * URIs aus {@code resource_link}-Referenzen (v2.3.0):
     * URI → Registrierungszeit (Epoch-millis).
     */
    private final Map<String, Long> linkedUris = new LinkedHashMap<String, Long>();

    /**
     * Liefert die Ressourcen-Liste (als {@link ArrayNode}); ruft den Agent
     * nur auf, wenn der Cache leer oder abgelaufen ist.
     *
     * @param client       Agent-Client
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect/Read-Timeout in Millisekunden
     * @param ttlMs        Gültigkeitsdauer des Caches in Millisekunden
     * @throws IOException Agent nicht erreichbar oder Antwort nicht parsebar
     */
    public ArrayNode getResources(NotesAgentClient client, String agentBaseUrl,
                                  int timeoutMs, long ttlMs) throws IOException {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAtMs) < ttlMs) {
            return cached;
        }
        ArrayNode fresh = parse(client.fetchResourceList(agentBaseUrl, timeoutMs));
        uris = collectUris(fresh);
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
     * true, wenn die URI in der (gecachten) Ressourcen-Liste enthalten ist
     * oder als Resource-Link referenziert wurde (v2.3.0, solange die
     * Referenz nicht abgelaufen ist). Führt zu false, wenn noch kein Cache
     * existiert und keine Referenz gemerkt ist.
     */
    public boolean hasUri(String uri) {
        return hasUri(uri, DominoMcpServlet.TOOLS_CACHE_TTL_MS);
    }

    /** {@link #hasUri(String)} mit expliziter Link-TTL (Tests). */
    boolean hasUri(String uri, long ttlMs) {
        if (uris != null && uris.contains(uri)) {
            return true;
        }
        return isLinkedValid(uri, ttlMs);
    }

    /**
     * Merkt sich die URIs der {@code resource_link}-Content-Items einer
     * {@code resources/read}-Antwort (v2.3.0) und räumt abgelaufene
     * Referenzen auf. Nur Items mit {@code type:"resource_link"} und
     * nicht-leerem {@code uri} (String) zählen; die übrigen Felder
     * (name, description, mimeType) werden ignoriert. Nicht-Array/null
     * wird leise ignoriert.
     *
     * @param contents {@code contents}-Array der Agent-Antwort (resources/read)
     * @param ttlMs    Gültigkeitsdauer der Referenzen in Millisekunden
     */
    public synchronized void addLinkedUris(JsonNode contents, long ttlMs) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = linkedUris.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= ttlMs) {
                it.remove();
            }
        }
        if (contents == null || !contents.isArray()) {
            return;
        }
        for (JsonNode item : contents) {
            if (item == null || !item.isObject()) {
                continue;
            }
            JsonNode type = item.get("type");
            if (type == null || !"resource_link".equals(type.asText())) {
                continue;
            }
            JsonNode uriNode = item.get("uri");
            if (uriNode == null || !uriNode.isTextual()) {
                continue;
            }
            String uri = uriNode.asText().trim();
            if (!uri.isEmpty()) {
                linkedUris.put(uri, now);
            }
        }
    }

    private synchronized boolean isLinkedValid(String uri, long ttlMs) {
        Long registeredAt = linkedUris.get(uri);
        return registeredAt != null && (System.currentTimeMillis() - registeredAt) < ttlMs;
    }

    /** Cache verwerfen (Ressourcen-Liste und Resource-Link-Referenzen). */
    public synchronized void invalidate() {
        cached = null;
        uris = null;
        cachedAtMs = 0L;
        linkedUris.clear();
    }

    /**
     * Parst die Agent-Antwort auf {@code op=reslist} (resources-Array).
     *
     * @throws IOException Antwort enthält keine Ressourcen-Liste
     */
    static ArrayNode parse(String agentJson) throws IOException {
        JsonNode root = MAPPER.readTree(agentJson);
        JsonNode arr;
        if (root != null && root.isArray()) {
            arr = root;
        } else if (root != null && root.isObject() && root.get("resources") != null
                && root.get("resources").isArray()) {
            arr = root.get("resources");
        } else {
            throw new IOException("Agent did not return a resource list "
                    + "(expected: {\"status\":\"ok\",\"resources\":[...]} or [...])");
        }
        return (ArrayNode) arr;
    }

    /** Sammelt die URIs der Ressourcen-Einträge (für die Read-Validierung). */
    private static List<String> collectUris(ArrayNode arr) {
        List<String> out = new ArrayList<String>();
        for (JsonNode r : arr) {
            if (r == null || !r.isObject()) {
                continue;
            }
            JsonNode uri = r.get("uri");
            if (uri != null && uri.isTextual() && !uri.asText().trim().isEmpty()) {
                out.add(uri.asText().trim());
            }
        }
        return Collections.unmodifiableList(out);
    }
}
