import java.io.IOException;

/**
 * Fehler bei der HTTP-Kommunikation mit dem Notes-Web-Agent.
 *
 * Wird geworfen, wenn der Agent nicht erreichbar ist, mit einem Fehlerstatus
 * antwortet oder ein Timeout auftritt. Die Servlet mappt diese Ausnahme auf
 * den JSON-RPC-Error MCP_1003 (-32001).
 */
public class NotesAgentException extends IOException {

    private static final long serialVersionUID = 1L;

    /** HTTP-Status des Agent (0 = keine Antwort/Timeout/ungültige URL). */
    private final int httpStatus;

    public NotesAgentException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
