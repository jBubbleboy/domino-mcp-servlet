import java.io.IOException;

/**
 * Body-Größen-Guardrail der MCP-Servlet.
 *
 * <ul>
 *   <li>{@code tooLarge}: empfangener Body überschreitet
 *       {@code mcpMaxPayloadBytes} (Fast-Fail statt rohem HTTP-500-HTML des
 *       Domino-HTTP-Tasks, der bei {@code Content-Length ≥ 10 240 000}
 *       abbricht).</li>
 *   <li>sonst: {@code Content-Length} (deklariert) und tatsächlich empfangene
 *       Bytes weichen ab — Datenverlust/Replay-Zone des Domino-HTTP-Tasks
 *       (64-KB-Buffer). Der Body ist nicht vertrauenswürdig und wird
 *       abgewiesen.</li>
 * </ul>
 */
public class BodySizeException extends IOException {

    private static final long serialVersionUID = 1L;

    /** true = Payload zu groß; false = Größen-Mismatch (Replay/Datenverlust). */
    private final boolean tooLarge;

    public BodySizeException(String message, boolean tooLarge) {
        super(message);
        this.tooLarge = tooLarge;
    }

    public boolean isTooLarge() {
        return tooLarge;
    }
}
