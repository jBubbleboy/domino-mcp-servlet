import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void parsesObjectWithTools() throws Exception {
        String json = "{\"status\":\"ok\",\"tools\":["
                + "{\"name\":\"@View\",\"description\":\"Dokumente aus Ansicht\",\"inputSchema\":{\"type\":\"object\"}},"
                + "{\"name\":\"@Echo\"}]}";
        List<ToolRegistry.ToolDefinition> tools = ToolRegistry.parse(json);
        assertEquals(2, tools.size());
        assertEquals("@View", tools.get(0).name);
        assertEquals("Dokumente aus Ansicht", tools.get(0).description);
        assertEquals("object", tools.get(0).inputSchema.get("type").asText());
        assertEquals("@Echo", tools.get(1).name);
        assertEquals("", tools.get(1).description);
        assertTrue(tools.get(1).inputSchema.isEmpty());
    }

    @Test
    void parseKeepsAdditionalFieldsInEntry() throws Exception {
        String json = "{\"status\":\"ok\",\"tools\":["
                + "{\"name\":\"@View\",\"description\":\"Dokumente aus Ansicht\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"outputSchema\":{\"type\":\"object\",\"properties\":{\"docs\":{\"type\":\"string\"}}},"
                + "\"annotations\":{\"readOnlyHint\":true}}]}";
        List<ToolRegistry.ToolDefinition> tools = ToolRegistry.parse(json);
        assertEquals(1, tools.size());
        com.fasterxml.jackson.databind.JsonNode entry = tools.get(0).entry;
        assertEquals("@View", entry.get("name").asText());
        assertEquals("Dokumente aus Ansicht", entry.get("description").asText());
        assertEquals("object", entry.get("inputSchema").get("type").asText());
        assertEquals("object", entry.get("outputSchema").get("type").asText());
        assertEquals(true, entry.get("annotations").get("readOnlyHint").asBoolean());
    }

    @Test
    void entryDefaultsAppliedWhenFieldsMissing() throws Exception {
        List<ToolRegistry.ToolDefinition> tools = ToolRegistry.parse("[{\"name\":\"@A\"}]");
        com.fasterxml.jackson.databind.JsonNode entry = tools.get(0).entry;
        assertEquals("@A", entry.get("name").asText());
        assertEquals("", entry.get("description").asText());
        assertTrue(entry.get("inputSchema").isObject());
        assertTrue(entry.get("inputSchema").isEmpty());
    }

    @Test
    void parsesBareArray() throws Exception {
        List<ToolRegistry.ToolDefinition> tools = ToolRegistry.parse("[{\"name\":\"@A\"}]");
        assertEquals(1, tools.size());
        assertEquals("@A", tools.get(0).name);
    }

    @Test
    void skipsEntriesWithoutName() throws Exception {
        List<ToolRegistry.ToolDefinition> tools = ToolRegistry.parse(
                "{\"tools\":[{\"description\":\"ohne Name\"},{\"name\":\"   \"},{\"name\":\"@X\"}]}");
        assertEquals(1, tools.size());
        assertEquals("@X", tools.get(0).name);
    }

    @Test
    void missingToolsFails() {
        assertThrows(IOException.class, () -> ToolRegistry.parse("{\"status\":\"ok\"}"));
        assertThrows(IOException.class, () -> ToolRegistry.parse("\"hello\""));
    }

    @Test
    void invalidJsonFails() {
        assertThrows(IOException.class, () -> ToolRegistry.parse("{broken"));
    }

    @Test
    void cacheExpiresAfterTtl() throws Exception {
        try (AgentStubServer stubServer = new AgentStubServer()) {
            final AtomicInteger calls = new AtomicInteger();
            stubServer.stub().setHandler((method, body) -> {
                calls.incrementAndGet();
                return AgentStub.Response.ok(
                        "{\"status\":\"ok\",\"tools\":[{\"name\":\"@T\"}]}");
            });
            NotesAgentClient client = new NotesAgentClient();
            ToolRegistry registry = new ToolRegistry();
            String base = stubServer.agentBaseUrl();

            List<ToolRegistry.ToolDefinition> first =
                    registry.getTools(client, base, 5000, 1000);
            assertEquals(1, first.size());
            registry.getTools(client, base, 5000, 1000);
            assertEquals(1, calls.get(), "zweiter Call muss aus dem Cache kommen");

            Thread.sleep(1100);
            registry.getTools(client, base, 5000, 1000);
            assertEquals(2, calls.get(), "nach TTL-Abglauf muss neu abgerufen werden");
        }
    }

    @Test
    void getCachedReturnsEmptyWhenNoCache() {
        assertTrue(new ToolRegistry().getCached().isEmpty());
    }
}
