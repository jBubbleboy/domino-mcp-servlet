import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-Tests für {@link ResourceRegistry} — v2.3.0:
 * {@code resource_link}-Referenzen freischalten ausgeblendete Ressourcen-URIs
 * (TTL-begrenzt), übrige Content-Items bleiben ohne Effekt.
 */
class ResourceRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    private static String link(String uri) {
        return "{\"type\":\"resource_link\",\"uri\":\"" + uri + "\",\"name\":\"L\"}";
    }

    @Test
    void hasUriFalseWithoutCacheOrLinks() {
        ResourceRegistry reg = new ResourceRegistry();
        assertFalse(reg.hasUri("domino://mcp/guide/hub"));
    }

    @Test
    void hasUriTrueForListedUri() throws Exception {
        try (AgentStubServer stub = new AgentStubServer()) {
            stub.stub().setHandler((method, body) -> {
                if (body != null && body.get("op") != null
                        && "reslist".equals(body.get("op").asText())) {
                    return AgentStub.Response.ok(
                            "{\"status\":\"ok\",\"resources\":["
                            + "{\"uri\":\"domino://mcp/guide/hub\",\"name\":\"Hub\"}]}");
                }
                return AgentStub.Response.ok(
                        "{\"status\":\"error\",\"code\":\"BAD_OP\",\"message\":\"unbekannte op\"}");
            });
            ResourceRegistry reg = new ResourceRegistry();
            reg.getResources(new NotesAgentClient(), stub.agentBaseUrl(), 5000, 60_000L);
            assertTrue(reg.hasUri("domino://mcp/guide/hub"));
            assertFalse(reg.hasUri("domino://mcp/skill/hidden"));
        }
    }

    @Test
    void linkedUriIsAccepted() throws Exception {
        ResourceRegistry reg = new ResourceRegistry();
        reg.addLinkedUris(tree("[" + link("domino://mcp/skill/hidden") + "]"), 60_000L);
        assertTrue(reg.hasUri("domino://mcp/skill/hidden", 60_000L));
        assertFalse(reg.hasUri("domino://mcp/skill/other", 60_000L));
    }

    @Test
    void linkedUriExpiresAfterTtl() throws Exception {
        ResourceRegistry reg = new ResourceRegistry();
        reg.addLinkedUris(tree("[" + link("domino://mcp/skill/hidden") + "]"), 50L);
        assertTrue(reg.hasUri("domino://mcp/skill/hidden", 50L));
        Thread.sleep(80L);
        assertFalse(reg.hasUri("domino://mcp/skill/hidden", 50L));
    }

    @Test
    void reAddRefreshesTimestamp() throws Exception {
        ResourceRegistry reg = new ResourceRegistry();
        String json = "[" + link("domino://mcp/skill/hidden") + "]";
        reg.addLinkedUris(tree(json), 100L);
        Thread.sleep(60L);
        reg.addLinkedUris(tree(json), 100L);
        Thread.sleep(60L);
        assertTrue(reg.hasUri("domino://mcp/skill/hidden", 100L));
    }

    @Test
    void onlyResourceLinkItemsAreCounted() throws Exception {
        ResourceRegistry reg = new ResourceRegistry();
        reg.addLinkedUris(tree(
                "["
                + "{\"uri\":\"domino://a/text\",\"mimeType\":\"text/plain\",\"text\":\"x\"},"
                + "{\"uri\":\"domino://a/blob\",\"mimeType\":\"application/octet-stream\"},"
                + link("domino://a/linked") + ","
                + "{\"type\":\"resource_link\",\"uri\":\"   \"},"
                + "{\"type\":\"resource_link\"},"
                + "null"
                + "]"), 60_000L);
        assertFalse(reg.hasUri("domino://a/text", 60_000L));
        assertFalse(reg.hasUri("domino://a/blob", 60_000L));
        assertTrue(reg.hasUri("domino://a/linked", 60_000L));
    }

    @Test
    void nullAndNonArrayContentsAreIgnored() throws Exception {
        ResourceRegistry reg = new ResourceRegistry();
        reg.addLinkedUris(null, 60_000L);
        reg.addLinkedUris(tree("{\"uri\":\"domino://a/obj\"}"), 60_000L);
        reg.addLinkedUris(tree("42"), 60_000L);
        assertFalse(reg.hasUri("domino://a/obj", 60_000L));
    }

    @Test
    void invalidateClearsLinkedUris() throws Exception {
        ResourceRegistry reg = new ResourceRegistry();
        reg.addLinkedUris(tree("[" + link("domino://mcp/skill/hidden") + "]"), 60_000L);
        assertTrue(reg.hasUri("domino://mcp/skill/hidden", 60_000L));
        reg.invalidate();
        assertFalse(reg.hasUri("domino://mcp/skill/hidden", 60_000L));
    }
}
