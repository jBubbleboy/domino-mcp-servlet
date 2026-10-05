import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MCP-Server-Name (Init-Param {@code mcpServerName}):
 * <ul>
 *   <li>Default "Domino" (ohne Init-Param)</li>
 *   <li>gueltige Werte werden uebernommen (getrimmt)</li>
 *   <li>leer/blank => Default "Domino"</li>
 * </ul>
 */
class ServerNameInitTest {

    /** Startet die Servlet mit einem Test-ServletConfig und liest den Server-Namen aus. */
    private static String nameFor(String paramValue) throws Exception {
        DominoMcpServlet servlet = new DominoMcpServlet();
        Map<String, String> params = new HashMap<String, String>();
        if (paramValue != null) {
            params.put("mcpServerName", paramValue);
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
        return servlet.serverName();
    }

    @Test
    void defaultWithoutParam() throws Exception {
        assertEquals("Domino", nameFor(null));
    }

    @Test
    void validValueIsAppliedAndTrimmed() throws Exception {
        assertEquals("DominoQA", nameFor("DominoQA"));
        assertEquals("QA", nameFor("  QA  "));
    }

    @Test
    void emptyValueFallsBackToDefault() throws Exception {
        assertEquals("Domino", nameFor(""));
        assertEquals("Domino", nameFor("   "));
    }
}
