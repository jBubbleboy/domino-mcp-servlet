import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * Startet einen lokalen Jetty mit {@link AgentStub} unter
 * {@code /mcp_tools.nsf/McpGateway} — für Tests, die den Agent ohne die
 * DominoMcpServlet brauchen (Client- und Registry-Tests).
 */
public class AgentStubServer implements AutoCloseable {

    private final Server server;
    private final AgentStub stub;

    public AgentStubServer() throws Exception {
        stub = new AgentStub();
        server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(stub), "/mcp_tools.nsf/McpGateway");
        server.setHandler(ctx);
        server.start();
    }

    /** Der Agent-Stub (für Handler-Setup und Assertions). */
    public AgentStub stub() {
        return stub;
    }

    /** Agent-Base-URL: {@code http://127.0.0.1:<port>/mcp_tools.nsf/McpGateway}. */
    public String agentBaseUrl() {
        return "http://127.0.0.1:" + server.getURI().getPort() + "/mcp_tools.nsf/McpGateway";
    }

    @Override
    public void close() throws Exception {
        server.stop();
    }
}
