import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP-Client für den Notes-Web-Agent (MCP-Gateway in der Notes-Datenbank).
 *
 * <p>Der Agent ist ein LotusScript-Web-Agent, dessen Print-Ausgabe JSON ist
 * (Details: {@code AGENT_CONTRACT.md}). Der Client spricht ihn ausschließlich
 * per HTTP-POST mit einem JSON-Body an:
 *
 * <pre>
 * Tool-Liste:  POST {agentBaseUrl}?open   Body: {{"op":"list"}}
 * Tool-Call:   POST {agentBaseUrl}?open   Body: {{"op":"call","tool":"&lt;name&gt;","params":{{...}}}}
 * </pre>
 *
 * <p>{@code agentBaseUrl} ist immer
 * {@code {scheme}://{eigener Host}[:port]/mcp_tools.nsf/McpGateway} — die
 * übergebende Servlet bildet sie aus dem einkommenden Request (keine externe
 * Konfiguration, s. {@link DominoMcpServlet#agentBaseUrl}).
 *
 * <p>Der {@code ?open} am Ende der Basis-URL ist zwingend: Ohne diesen
 * Trigger behandelt Domino die URL als Dokumentzugriff und startet den Agent
 * nicht (falsch: {@code .../McpGateway}, richtig: {@code .../McpGateway?open}).
 * Der eigentliche Inhalt (op, tool, params) kommt im JSON-Body, nicht in der
 * URL — dadurch bleiben die URLs kurz und lesbar und es gibt keine
 * URL-Längen- bzw. Kodierungsprobleme mehr.
 *
 * <p>Der Agent liest den JSON-Body über das {@code Request_Content}-Feld des
 * {@code DocumentContext} — die HCL-dokumentierte Art, einen POST-Body in
 * einem Domino-Web-Agent auszuwerten.
 */
public class NotesAgentClient {

    static final String OP_LIST = "list";
    static final String OP_CALL = "call";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Ruft die Tool-Definitionen aus der Notes-Datenbank ab.
     *
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect- und Read-Timeout in Millisekunden
     * @return roher JSON-Body des Agents
     *         (erwartet: {@code {"status":"ok","tools":[...]}})
     * @throws IOException Agent nicht erreichbar, Timeout oder HTTP-Fehler
     */
    public String fetchToolList(String agentBaseUrl, int timeoutMs) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("op", OP_LIST);
        return doPost(agentBaseUrl, timeoutMs, body);
    }

    /**
     * Führt einen Tool-Call im Agent aus.
     *
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect- und Read-Timeout in Millisekunden
     * @param toolName     Name des Tools (aus der Notes-DB)
     * @param arguments    Argumente als JSON-Objekt (null/leer = leeres Objekt)
     * @return roher JSON-Body des Agents
     * @throws IOException Agent nicht erreichbar, Timeout oder HTTP-Fehler
     */
    public String callTool(String agentBaseUrl, int timeoutMs, String toolName,
                           Map<String, Object> arguments) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("op", OP_CALL);
        body.put("tool", toolName);
        ObjectNode params = body.putObject("params");
        if (arguments != null) {
            for (Map.Entry<String, Object> e : arguments.entrySet()) {
                params.set(e.getKey(), MAPPER.valueToTree(e.getValue()));
            }
        }
        return doPost(agentBaseUrl, timeoutMs, body);
    }

    private String doPost(String agentBaseUrl, int timeoutMs, ObjectNode body) throws IOException {
        // ?open ist zwingend: nur damit behandelt Domino die URL als Agent-Aufruf.
        String url = agentBaseUrl + "?open";
        String payload;
        try {
            payload = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // Kann praktisch nicht passieren (der Body wird selbst mit Jackson gebaut).
            throw new NotesAgentException("Agent-Body nicht serialisierbar: " + e.getMessage(), 0);
        }
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
        } catch (MalformedURLException e) {
            throw new NotesAgentException("Ungültige Agent-URL: " + url, 0);
        }
        con.setRequestMethod("POST");
        con.setConnectTimeout(timeoutMs);
        con.setReadTimeout(timeoutMs);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);

        // Request-Body schreiben (hier wird auch verbunden: Connect-/Timeout-Fehler)
        try (OutputStream os = con.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        } catch (SocketTimeoutException e) {
            throw new NotesAgentException(
                    "Timeout nach " + (timeoutMs / 1000) + "s beim Agent-Call: " + url, 0);
        } catch (ConnectException e) {
            throw new NotesAgentException("Agent nicht erreichbar (Connection refused): " + url, 0);
        } catch (UnknownHostException e) {
            throw new NotesAgentException("Host unbekannt: " + url, 0);
        }

        int status;
        try {
            status = con.getResponseCode();
        } catch (SocketTimeoutException e) {
            throw new NotesAgentException(
                    "Timeout nach " + (timeoutMs / 1000) + "s beim Agent-Call: " + url, 0);
        } catch (ConnectException e) {
            throw new NotesAgentException("Agent nicht erreichbar (Connection refused): " + url, 0);
        } catch (UnknownHostException e) {
            throw new NotesAgentException("Host unbekannt: " + url, 0);
        }
        try {
            if (status != 200) {
                String respBody = readBody(con.getErrorStream());
                String detail = respBody.trim().isEmpty() ? "" : " | " + truncate(respBody.trim(), 300);
                throw new NotesAgentException(
                        "Agent antwortete mit HTTP " + status + ": " + url + detail, status);
            }
            return readBody(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int n;
        while ((n = in.read(block)) != -1) {
            buf.write(block, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
