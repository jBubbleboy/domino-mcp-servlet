import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
import java.util.UUID;

/**
 * HTTP-Client für den Notes-Web-Agent (MCP-Gateway in der Notes-Datenbank).
 *
 * <p>Der Agent ist ein LotusScript-Web-Agent, dessen Print-Ausgabe JSON ist
 * (Details: {@code AGENT_CONTRACT.md}). Der Client spricht ihn ausschließlich
 * per HTTP-POST mit einem JSON-Body an:
 *
 * <pre>
 * Tool-Liste:       POST {agentBaseUrl}?open   Body: {{"op":"list"}}
 * Tool-Call:        POST {agentBaseUrl}?open   Body: {{"op":"call","tool":"&lt;name&gt;","params":{{...}}}}
 * Ressourcen-Liste: POST {agentBaseUrl}?open   Body: {{"op":"reslist"}}
 * Ressourcen-Read:  POST {agentBaseUrl}?open   Body: {{"op":"resread","uri":"&lt;uri&gt;"}}
 * Prompt-Liste:     POST {agentBaseUrl}?open   Body: {{"op":"promptlist"}}
 * Prompt-Get:       POST {agentBaseUrl}?open   Body: {{"op":"promptget","name":"&lt;name&gt;","arguments":{{...}}}}
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
 *
 * <p><b>Authentifizierung (Per-User-Domino-Identität):</b> Der Client kann
 * einen {@code Authorization}-Header an den internen Agent-Call weiterreichen
 * (Konstruktor mit Argument). Die Servlet übergibt dabei ausschließlich
 * Credentials, die der Domino-HTTP-Task am einkommenden Request bereits
 * validiert hat (s. {@link DominoMcpServlet#authForAgentCall}). So läuft der
 * Web-Agent mit der Identität des MCP-Users (Session.UserName) und kann
 * User-ACLs durchsetzen. Ohne Header bleibt der Agent-Call anonym.
 */
public class NotesAgentClient {

    static final String OP_LIST = "list";
    static final String OP_CALL = "call";
    static final String OP_RES_LIST = "reslist";
    static final String OP_RES_READ = "resread";
    static final String OP_PROMPT_LIST = "promptlist";
    static final String OP_PROMPT_GET = "promptget";
    static final String OP_ENV = "env";
    static final String OP_FILECALL = "filecall";

    /**
     * Schwellwert für das Datei-Handover: Agent-Bodies, die diese Größe in
     * Bytes überschreiten, werden nicht mehr über {@code Request_Content}
     * gesendet (dort nur intakt bis 32 766 B — Domino 64-KB-HTTP-Task-Buffer),
     * sondern per temporärer Datei ({@code op=filecall}). Messbericht:
     * DomAI_MCP_McpGateway/docs/2026-09-18_http_size_limits.md.
     */
    static final int MAX_AGENT_BODY_INLINE = 32_000;

    // ESCAPE_NON_ASCII: nicht-ASCII-Zeichen werden im Agent-Body als
    // Unicode-Escapes (backslash-u-XXXX) emittiert, statt als rohe UTF-8-Bytes.
    // Domino dekodiert den rohen POST-Body naemlich mit der Server-Codepage
    // (CP437/CP850), NICHT mit dem deklarierten charset=utf-8 (fuer rohe
    // JSON-Bodies; bei Formulareinreichung gilt das nicht). Rohe UTF-8-Bytes
    // -> Mojibake. ASCII auf dem Kabel ist codepage-unabhaengig; der Agent
    // (dnWeb.unescapeJsonStr) dekodiert die Escapes zurueck. Siehe AGENT_CONTRACT.md.
    private static final ObjectMapper MAPPER;

    static {
        // ESCAPE_NON_ASCII ist eine JsonGenerator-Feature; sie wird auf der
        // JsonFactory gesetzt (TokenStreamFactory.enable), nicht im Builder.
        JsonFactory jf = new JsonFactory();
        jf.enable(JsonGenerator.Feature.ESCAPE_NON_ASCII);
        MAPPER = new ObjectMapper(jf);
    }

    /**
     * Authorization-Header für den internen Agent-Call; null = anonymer Call.
     * Nur von der Servlet gesetzt — und zwar ausschließlich mit Credentials,
     * die der Domino-HTTP-Task bereits am einkommenden Request validiert hat.
     */
    private final String authorizationHeader;

    /** Erzeugt einen Client ohne Credentials (anonyme Agent-Calls). */
    public NotesAgentClient() {
        this(null);
    }

    /**
     * Erzeugt einen Client, der den übergebenen Authorization-Header an alle
     * Agent-Calls weiterreicht.
     *
     * @param authorizationHeader Wert des Authorization-Headers
     *                            (z. B. {@code Basic base64(user:pass)});
     *                            null = Header wird nicht gesendet
     */
    public NotesAgentClient(String authorizationHeader) {
        this.authorizationHeader = authorizationHeader;
    }

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

    /**
     * Ruft die Ressourcen-Liste ({@code resources/list}) aus der Notes-Datenbank ab.
     *
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect- und Read-Timeout in Millisekunden
     * @return roher JSON-Body des Agents
     *         (erwartet: {@code {"status":"ok","resources":[...]}})
     * @throws IOException Agent nicht erreichbar, Timeout oder HTTP-Fehler
     */
    public String fetchResourceList(String agentBaseUrl, int timeoutMs) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("op", OP_RES_LIST);
        return doPost(agentBaseUrl, timeoutMs, body);
    }

    /**
     * Liefert den Content einer Ressource ({@code resources/read}) aus dem Agent.
     *
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect- und Read-Timeout in Millisekunden
     * @param uri          Resource-URI (aus der Notes-DB)
     * @return roher JSON-Body des Agents
     *         (erwartet: {@code {"status":"ok","contents":[...]}})
     * @throws IOException Agent nicht erreichbar, Timeout oder HTTP-Fehler
     */
    public String readResource(String agentBaseUrl, int timeoutMs, String uri) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("op", OP_RES_READ);
        body.put("uri", uri);
        return doPost(agentBaseUrl, timeoutMs, body);
    }

    /**
     * Ruft die Prompt-Liste ({@code prompts/list}) aus der Notes-Datenbank ab.
     *
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect- und Read-Timeout in Millisekunden
     * @return roher JSON-Body des Agents
     *         (erwartet: {@code {"status":"ok","prompts":[...]}})
     * @throws IOException Agent nicht erreichbar, Timeout oder HTTP-Fehler
     */
    public String fetchPromptList(String agentBaseUrl, int timeoutMs) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("op", OP_PROMPT_LIST);
        return doPost(agentBaseUrl, timeoutMs, body);
    }

    /**
     * Liefert einen Prompt ({@code prompts/get}) aus dem Agent.
     *
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect- und Read-Timeout in Millisekunden
     * @param name         Name des Prompts (aus der Notes-DB)
     * @param arguments    Prompt-Argumente als JSON-Objekt (null/leer = leeres Objekt)
     * @return roher JSON-Body des Agents
     *         (erwartet: {@code {"status":"ok","messages":[...]}})
     * @throws IOException Agent nicht erreichbar, Timeout oder HTTP-Fehler
     */
    public String getPrompt(String agentBaseUrl, int timeoutMs, String name,
                            Map<String, Object> arguments) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("op", OP_PROMPT_GET);
        body.put("name", name);
        ObjectNode args = body.putObject("arguments");
        if (arguments != null) {
            for (Map.Entry<String, Object> e : arguments.entrySet()) {
                args.set(e.getKey(), MAPPER.valueToTree(e.getValue()));
            }
        }
        return doPost(agentBaseUrl, timeoutMs, body);
    }

    /**
     * Fragt die Agent-Konfiguration ab ({@code op=env}): Verzeichnis für das
     * Datei-Handover und das Agent-Payload-Limit. Kleiner Inline-Body —
     * unbeeinflusst von den {@code Request_Content}-Größenzonen.
     *
     * @param agentBaseUrl Basis-URL des Agents ohne Query-String
     * @param timeoutMs    Connect- und Read-Timeout in Millisekunden
     * @return roher JSON-Body des Agents
     *         (erwartet: {@code {"status":"ok","env":{...}}})
     * @throws IOException Agent nicht erreichbar, Timeout oder HTTP-Fehler
     */
    public String fetchEnv(String agentBaseUrl, int timeoutMs) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("op", OP_ENV);
        return doPost(agentBaseUrl, timeoutMs, body);
    }

    private String doPost(String agentBaseUrl, int timeoutMs, ObjectNode body) throws IOException {
        byte[] payloadBytes;
        try {
            payloadBytes = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // Kann praktisch nicht passieren (der Body wird selbst mit Jackson gebaut).
            throw new NotesAgentException("Agent body could not be serialized: " + e.getMessage(), 0);
        }
        if (payloadBytes.length > MAX_AGENT_BODY_INLINE) {
            // Datei-Handover: der rohe Body wäre in Request_Content korrupt
            // (Domino-64-KB-HTTP-Task-Buffer); stattdessen wandert er in eine
            // temporäre Datei und der Agent holt ihn per op=filecall.
            return doPostFileCall(agentBaseUrl, timeoutMs, payloadBytes);
        }
        return doPostInline(agentBaseUrl + "?open", timeoutMs,
                new String(payloadBytes, StandardCharsets.UTF_8));
    }

    /**
     * Datei-Handover für Agent-Bodies &gt; {@link #MAX_AGENT_BODY_INLINE}:
     * der Payload wird nach {@code <env.fileCallDir>/<UUID>.json} geschrieben,
     * ein kleines Envelope-JSON ({@code op=filecall}) mit {@code file} und
     * absolutem {@code path} an den Agent geschickt; der Agent liest die
     * Datei (bevorzugt ueber {@code path}), verifiziert (size/checkSum),
     * verarbeitet den
     * Inhalt als regulären Agent-Body und löscht die Datei. Die Servlet
     * löscht die Datei zusätzlich best-effort im {@code finally}.
     *
     * @throws IOException Agent nicht erreichbar, Handover-Setup-Fehler
     *                     (NotesAgentException 413 = Payload über Agent-Limit,
     *                     500 = Datei-Verzeichnis/Datei nicht beschreibbar)
     */
    private String doPostFileCall(String agentBaseUrl, int timeoutMs, byte[] payloadBytes)
            throws IOException {
        FileCallEnv.Env env;
        try {
            env = FileCallEnv.get(this, agentBaseUrl, timeoutMs, 60_000L);
        } catch (IOException e) {
            throw new NotesAgentException("FileCall env query failed (agent unreachable or "
                    + "invalid env response): " + e.getMessage(), 0);
        }
        if (payloadBytes.length > env.maxFileCallBytes) {
            throw new NotesAgentException("Payload exceeds agent filecall limit: "
                    + payloadBytes.length + " > " + env.maxFileCallBytes
                    + " bytes (raise server HTTP limit + FILECALL_MAX_BYTES/mcpMaxPayloadBytes)", 413);
        }
        File dir = new File(env.fileCallDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new NotesAgentException("FileCallDir not creatable: " + dir.getAbsolutePath(), 500);
        }
        File file = new File(dir, UUID.randomUUID().toString() + ".json");
        try {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(payloadBytes);
            }
        } catch (IOException e) {
            file.delete();
            throw new NotesAgentException("FileCall write failed: " + e.getMessage(), 500);
        }
        long checkSum = 0;
        for (byte b : payloadBytes) {
            checkSum = (checkSum + (b & 0xFF)) % 0x7FFFFFFF;
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("op", OP_FILECALL);
        envelope.put("file", file.getName());
        // Absoluter Datei-Pfad: der Agent bevorzugt ihn (fileCallDir-Praefix-
        // Check als Traversing-Schutz). "file" bleibt als Kompat-Field.
        envelope.put("path", file.getAbsolutePath());
        envelope.put("size", (long) payloadBytes.length);
        envelope.put("checkSum", checkSum);
        String response;
        try {
            // Kleines Envelope-JSON (immer < MAX_AGENT_BODY_INLINE) → Inline-Pfad.
            response = doPostInline(agentBaseUrl + "?open", timeoutMs,
                    MAPPER.writeValueAsString(envelope));
        } finally {
            // Best-effort-Cleanup (der Agent löscht die Datei nach dem Read
            // ebenfalls; hier ist die zweite Lösch-Instanz der Backstop, z. B.
            // falls der Agent fehlgeschlagen ist, bevor er sie löschen konnte).
            file.delete();
        }
        return response;
    }

    private String doPostInline(String url, int timeoutMs, String payload) throws IOException {
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
        } catch (MalformedURLException e) {
            throw new NotesAgentException("Invalid agent URL: " + url, 0);
        }
        con.setRequestMethod("POST");
        con.setConnectTimeout(timeoutMs);
        con.setReadTimeout(timeoutMs);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (authorizationHeader != null) {
            // Per-User-Domino-Identität: der einzige weitergeleitete Header
            // (Whitelist-Prinzip, s. Klassen-Javadoc) — damit der Web-Agent
            // mit Session.UserName des MCP-Users läuft.
            con.setRequestProperty("Authorization", authorizationHeader);
        }
        con.setDoOutput(true);

        // Request-Body schreiben (hier wird auch verbunden: Connect-/Timeout-Fehler)
        try (OutputStream os = con.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        } catch (SocketTimeoutException e) {
            throw new NotesAgentException(
                    "Timeout after " + (timeoutMs / 1000) + "s on agent call: " + url, 0);
        } catch (ConnectException e) {
            throw new NotesAgentException("Agent unreachable (connection refused): " + url, 0);
        } catch (UnknownHostException e) {
            throw new NotesAgentException("Host unknown: " + url, 0);
        }

        int status;
        try {
            status = con.getResponseCode();
        } catch (SocketTimeoutException e) {
            throw new NotesAgentException(
                    "Timeout after " + (timeoutMs / 1000) + "s on agent call: " + url, 0);
        } catch (ConnectException e) {
            throw new NotesAgentException("Agent unreachable (connection refused): " + url, 0);
        } catch (UnknownHostException e) {
            throw new NotesAgentException("Host unknown: " + url, 0);
        }
        try {
            if (status != 200) {
                String respBody = readBody(con.getErrorStream());
                String detail = respBody.trim().isEmpty() ? "" : " | " + truncate(respBody.trim(), 300);
                throw new NotesAgentException(
                        "Agent responded with HTTP " + status + ": " + url + detail, status);
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
