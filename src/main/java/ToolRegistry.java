import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cache für die Tool-Liste, die die Notes-Datenbank bereitstellt.
 *
 * Die Servlet fragt die Tools über den Agent ({@code op=list}) ab und
 * merkt sich das Ergebnis für die übergebene TTL (Default der Servlet:
 * 60 s). Dadurch werden wiederholte
 * {@code tools/list}-Requests des LLM nicht bei jedem Mal als
 * HTTP-Rundlauf zur Notes-DB umgesetzt.
 *
 * Das Parsing ist bewusst tolerant: Der Agent darf entweder
 * {@code {"status":"ok","tools":[...]}} oder direkt ein JSON-Array
 * liefern; {@code description} und {@code inputSchema} sind optional.
 */
public class ToolRegistry {

    /** Ein Tool, wie es die Notes-DB (Agent op=list) definiert. */
    public static class ToolDefinition {
        public final String name;
        public final String description;
        public final ObjectNode inputSchema;
        /** Vollstaendiger Tool-Eintrag inkl. weiterer Felder (z. B. outputSchema,
         *  annotations), Defaults fuer name/description/inputSchema angewendet. */
        public final ObjectNode entry;

        ToolDefinition(String name, String description, ObjectNode inputSchema, ObjectNode entry) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.entry = entry;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile List<ToolDefinition> cached;
    private volatile long cachedAtMs;

    /**
     * Liefert die Tool-Liste; ruft den Agent nur auf, wenn der Cache
     * leer oder abgelaufen ist.
     *
     * @param client        Agent-Client
     * @param agentBaseUrl  Basis-URL des Agents ohne Query-String
     * @param timeoutMs     Connect/Read-Timeout in Millisekunden
     * @param ttlMs         Gültigkeitsdauer des Caches in Millisekunden
     * @throws IOException Agent nicht erreichbar oder Antwort nicht parsebar
     */
    public List<ToolDefinition> getTools(NotesAgentClient client, String agentBaseUrl,
                                         int timeoutMs, long ttlMs) throws IOException {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAtMs) < ttlMs) {
            return cached;
        }
        List<ToolDefinition> fresh = parse(client.fetchToolList(agentBaseUrl, timeoutMs));
        cached = fresh;
        cachedAtMs = System.currentTimeMillis();
        return fresh;
    }

    /**
     * Liefert den aktuell gemerkten Cache (leere Liste, wenn noch kein
     * Cache existiert). Dient als Fallback, wenn der Agent gerade
     * nicht erreichbar ist.
     */
    public List<ToolDefinition> getCached() {
        return cached == null ? Collections.<ToolDefinition>emptyList() : cached;
    }

    public boolean isCached() {
        return cached != null;
    }

    /** Cache verwerfen. */
    public void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    /**
     * Parst die Agent-Antwort auf {@code op=list}.
     *
     * @throws IOException Antwort enthält keine Tool-Liste
     */
    static List<ToolDefinition> parse(String agentJson) throws IOException {
        JsonNode root = MAPPER.readTree(agentJson);
        JsonNode toolsNode;
        if (root != null && root.isArray()) {
            toolsNode = root;
        } else if (root != null && root.isObject() && root.get("tools") != null && root.get("tools").isArray()) {
            toolsNode = root.get("tools");
        } else {
            throw new IOException("Agent did not return a tool list "
                    + "(expected: {\"status\":\"ok\",\"tools\":[...]} or [...])");
        }

        List<ToolDefinition> tools = new ArrayList<ToolDefinition>();
        for (JsonNode t : toolsNode) {
            if (t == null || !t.isObject()) {
                continue;
            }
            JsonNode name = t.get("name");
            if (name == null || !name.isTextual() || name.asText().trim().isEmpty()) {
                continue;
            }
            ObjectNode entry = ((ObjectNode) t).deepCopy();
            entry.put("name", name.asText().trim());
            String description = (t.get("description") != null && t.get("description").isTextual())
                    ? t.get("description").asText()
                    : "";
            entry.put("description", description);
            JsonNode schemaNode = t.get("inputSchema");
            ObjectNode schema = (schemaNode != null && schemaNode.isObject())
                    ? (ObjectNode) schemaNode
                    : MAPPER.createObjectNode();
            entry.set("inputSchema", schema);
            tools.add(new ToolDefinition(name.asText().trim(), description, schema, entry));
        }
        return Collections.unmodifiableList(tools);
    }
}
