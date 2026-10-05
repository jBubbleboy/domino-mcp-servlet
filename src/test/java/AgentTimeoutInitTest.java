import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Agent-Timeout (Init-Param {@code mcpAgentTimeoutMs}):
 * <ul>
 *   <li>Default 30 000 ms (ohne Init-Param)</li>
 *   <li>gueltige Werte (ms) werden uebernommen</li>
 *   <li>ungueltige Werte (nicht-numerisch, &le; 0, &gt; Integer.MAX_VALUE)
 *       fallen zurueck auf den Default</li>
 * </ul>
 */
class AgentTimeoutInitTest {

    /** Startet die Servlet mit einem Test-ServletConfig und liest den Timeout aus. */
    private static int timeoutFor(String paramValue) throws Exception {
        DominoMcpServlet servlet = new DominoMcpServlet();
        Map<String, String> params = new HashMap<String, String>();
        if (paramValue != null) {
            params.put("mcpAgentTimeoutMs", paramValue);
        }
        servlet.init(new ServletConfig() {
            public String getServletName() {
                return "test";
            }

            public ServletContext getServletContext() {
                return null;
            }

            public String getInitParameter(String name) {
                return params.get(name);
            }

            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(params.keySet());
            }
        });
        return servlet.agentTimeoutMs();
    }

    @Test
    void defaultWithoutParam() throws Exception {
        assertEquals(30_000, timeoutFor(null));
    }

    @Test
    void validValueIsApplied() throws Exception {
        assertEquals(120_000, timeoutFor("120000"));
    }

    @Test
    void invalidValuesFallBackToDefault() throws Exception {
        assertEquals(30_000, timeoutFor("abc"));
        assertEquals(30_000, timeoutFor("-5"));
        assertEquals(30_000, timeoutFor("0"));
        assertEquals(30_000, timeoutFor(String.valueOf(Integer.MAX_VALUE + 1L)));
    }
}
