package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player's chat files over HTTP: upload, send, download, delete, the conversation entries that carry the files, and the access
 * table of the download route (another punishment, a removed message, a withdrawn share and a leaked Ban ID).
 */
@Tag("integration")
class PublicAttachmentApiIntegrationTest extends PublicApiTestBase {

    private static final String DOWNLOAD = "/public/attachments/";

    @TempDir
    Path files;

    private HttpClient http;

    /** A download with its status, body and lower-cased headers. */
    private record Bytes(int status, Map<String, String> headers) {

        String header(String name) {
            return this.headers.get(name.toLowerCase());
        }
    }

    @Override
    protected Map<String, Object> extraProperties() {
        Map<String, Object> properties = new HashMap<>(super.extraProperties());
        properties.put("blackhole.evidence.files.storage", "local");
        properties.put("blackhole.evidence.files.local.path", this.files.toString());
        properties.put("blackhole.evidence.scan.enabled", false);
        return properties;
    }

    @BeforeEach
    void client() {
        this.http = this.context.createBean(HttpClient.class, this.server.getURL());
    }

    @AfterEach
    void closeClient() {
        this.http.close();
    }

    /** One multipart file upload to {@code path} with {@code token}. */
    private Reply multipart(String path, String token, byte[] content, String fileName) {
        String boundary = "----chat-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        MutableHttpRequest<byte[]> request = HttpRequest.POST(path, body.toByteArray())
                .contentType("multipart/form-data; boundary=" + boundary);
        if (token != null) {
            request = request.bearerAuth(token);
        }
        HttpResponse<String> response;
        try {
            response = this.http.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked") HttpResponse<String> failed = (HttpResponse<String>) e.getResponse();
            response = failed;
        }
        return new Reply(response.code(), response.body() == null ? "" : response.body(), this.context.getBean(JsonMapper.class));
    }

    private Bytes download(String path, String token) {
        MutableHttpRequest<?> request = HttpRequest.GET(path).bearerAuth(token);
        HttpResponse<byte[]> response;
        try {
            response = this.http.toBlocking().exchange(request, byte[].class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked") HttpResponse<byte[]> failed = (HttpResponse<byte[]>) e.getResponse();
            response = failed;
        }
        Map<String, String> headers = new HashMap<>();
        response.getHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        return new Bytes(response.code(), headers);
    }

    private static byte[] png() {
        try {
            return EvidenceFixtures.png(24, 16);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** Uploads one file as the player and returns its attachment id. */
    private String stage(String token, byte[] content, String fileName) {
        Reply staged = multipart(DOWNLOAD.substring(0, DOWNLOAD.length() - 1), token, content, fileName);
        assertEquals(200, staged.status(), staged.body());
        return (String) staged.json().get("attachmentId");
    }

    /** Sends a message as the player naming the files and returns the message id. */
    private String send(String token, String text, String... attachmentIds) {
        Raw posted = playerPost("/public/messages", token, Map.of("text", text, "attachmentIds", List.of(attachmentIds)));
        assertEquals(200, posted.status(), posted.body());
        return (String) posted.json().get("messageId");
    }

    /** A punished player with an open appeal; returns the punishment and writes the session token into {@code tokenOut[0]}. */
    private String appealedPlayer(String owner, String key, String[] tokenOut) {
        String punishment = punish(owner, key);
        tokenOut[0] = sessionFor(punishment);
        fileAppeal(tokenOut[0]);
        return punishment;
    }

    @Test
    void aPlayerUploadsAPngAndGetsItsAttachmentId() {
        String[] token = new String[1];
        appealedPlayer("f1".repeat(64), "pub-upload", token);

        Reply staged = multipart("/public/attachments", token[0], png(), "shot.png");

        assertEquals(200, staged.status(), staged.body());
        assertEquals("shot.png", staged.json().get("name"));
        assertEquals("image/png", staged.json().get("contentType"));
        assertEquals(24, ((Number) staged.json().get("width")).intValue());
        assertTrue(staged.json().get("attachmentId") instanceof String);
    }

    @Test
    void anUploadNeedsAnOpenAppeal() {
        String punishment = punish("f2".repeat(64), "pub-noappeal");
        String token = sessionFor(punishment);

        Reply staged = multipart("/public/attachments", token, png(), "shot.png");

        assertEquals(409, staged.status(), staged.body());
        assertEquals("NO_APPEAL", staged.json().get("code"));
    }

    @Test
    void aVideoIsNotAChatFile() {
        String[] token = new String[1];
        appealedPlayer("f3".repeat(64), "pub-video", token);

        Reply staged = multipart("/public/attachments", token[0], EvidenceFixtures.mp4(2048), "clip.mp4");

        assertEquals(415, staged.status(), staged.body());
        assertEquals("UNSUPPORTED_TYPE", staged.json().get("code"));
    }

    @Test
    void aStagedFileIsNotDownloadableUntilAMessageSendsIt() {
        String[] token = new String[1];
        appealedPlayer("f4".repeat(64), "pub-staged", token);
        String attachment = stage(token[0], png(), "shot.png");

        assertEquals(404, download(DOWNLOAD + attachment, token[0]).status());
    }

    @Test
    void aSentImageDownloadsInlineWithTheSafeHeaders() {
        String[] token = new String[1];
        appealedPlayer("f5".repeat(64), "pub-inline", token);
        String attachment = stage(token[0], png(), "shot.png");
        send(token[0], "Here is the screenshot.", attachment);

        Bytes file = download(DOWNLOAD + attachment, token[0]);

        assertEquals(200, file.status());
        assertEquals("image/png", file.header("content-type"));
        assertEquals("nosniff", file.header("x-content-type-options"));
        assertEquals("sandbox", file.header("content-security-policy"));
        assertEquals("private, no-store", file.header("cache-control"));
        assertTrue(file.header("content-disposition").startsWith("inline"), file.header("content-disposition"));
    }

    @Test
    void aSentPdfIsAlwaysADownload() {
        String[] token = new String[1];
        appealedPlayer("f6".repeat(64), "pub-pdf", token);
        String attachment = stage(token[0], pdf(), "statement.pdf");
        send(token[0], "The statement is attached.", attachment);

        Bytes file = download(DOWNLOAD + attachment, token[0]);

        assertEquals(200, file.status());
        assertEquals("application/pdf", file.header("content-type"));
        assertTrue(file.header("content-disposition").startsWith("attachment"), file.header("content-disposition"));
        assertEquals("sandbox", file.header("content-security-policy"));
    }

    @Test
    void anotherPunishmentsFileIsNotFound() {
        String[] owner = new String[1];
        appealedPlayer("f7".repeat(64), "pub-owner", owner);
        String attachment = stage(owner[0], png(), "shot.png");
        send(owner[0], "mine", attachment);
        String[] other = new String[1];
        appealedPlayer("f8".repeat(64), "pub-other", other);

        assertEquals(404, download(DOWNLOAD + attachment, other[0]).status());
        assertEquals(404, download(DOWNLOAD + UUID.randomUUID(), other[0]).status(), "an unknown id gives the same answer");
        assertEquals(404, download(DOWNLOAD + "not-an-id", other[0]).status(), "a malformed id gives the same answer");
    }

    @Test
    void aRedactedMessageTakesItsFileAway() {
        String[] token = new String[1];
        String punishment = appealedPlayer("f9".repeat(64), "pub-redact", token);
        String attachment = stage(token[0], png(), "shot.png");
        String message = send(token[0], "Here is the screenshot.", attachment);
        assertEquals(200, download(DOWNLOAD + attachment, token[0]).status());

        Reply redacted = post("/case/" + punishment + "/messages/" + message + "/redact", Map.of("reason", "OTHER"), named(this.jwt.admin()));

        assertEquals(200, redacted.status(), redacted.body());
        assertEquals(404, download(DOWNLOAD + attachment, token[0]).status());
    }

    @Test
    void aLeakedBanIdIsForbiddenOnEveryFileRoute() {
        String[] token = new String[1];
        String punishment = appealedPlayer("fa".repeat(64), "pub-leak", token);
        String attachment = stage(token[0], png(), "shot.png");
        send(token[0], "before the leak", attachment);
        markLeaked(punishment);
        String limited = (String) login(banIdOf(punishment)).json().get("token");

        assertEquals(403, multipart("/public/attachments", limited, png(), "again.png").status(), "upload");
        assertEquals(403, download(DOWNLOAD + attachment, limited).status(), "download");
        assertEquals(403, call(HttpMethod.DELETE, DOWNLOAD + attachment, limited, null).status(), "delete");
    }

    @Test
    void aStagedFileCanBeRemovedOnceAndThenIsGone() {
        String[] token = new String[1];
        appealedPlayer("fb".repeat(64), "pub-delete", token);
        String attachment = stage(token[0], png(), "shot.png");

        assertEquals(204, call(HttpMethod.DELETE, DOWNLOAD + attachment, token[0], null).status());
        assertEquals(404, call(HttpMethod.DELETE, DOWNLOAD + attachment, token[0], null).status());
    }

    @Test
    void aMessageMayNotNameAFileStagedForAnotherPlayer() {
        String[] first = new String[1];
        appealedPlayer("fc".repeat(64), "pub-foreign-a", first);
        String attachment = stage(first[0], png(), "shot.png");
        String[] second = new String[1];
        appealedPlayer("fd".repeat(64), "pub-foreign-b", second);

        Raw posted = playerPost("/public/messages", second[0], Map.of("text", "borrowed", "attachmentIds", List.of(attachment)));

        assertEquals(400, posted.status(), posted.body());
    }

    @Test
    void aPlayersFileNeverCountsAsTeamEvidence() {
        String[] token = new String[1];
        String punishment = appealedPlayer("fe".repeat(64), "pub-count", token);
        String attachment = stage(token[0], png(), "shot.png");
        send(token[0], "the screenshot", attachment);

        Reply count = get("/punishment/" + punishment + "/evidence/count");

        assertEquals(0, ((Number) count.json().get("count")).intValue(), "the team figure");
        assertEquals(1, ((Number) count.json().get("playerSupplied")).intValue(), "the player's own figure");
    }

    @Test
    void theConversationEntryCarriesTheFilesOfItsMessage() {
        String[] token = new String[1];
        appealedPlayer("ff".repeat(64), "pub-entry", token);
        String attachment = stage(token[0], png(), "shot.png");
        send(token[0], "the screenshot", attachment);

        Raw read = playerGet("/public/conversation", token[0]);

        assertEquals(200, read.status(), read.body());
        @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) read.json().get("entries");
        @SuppressWarnings("unchecked") List<Map<String, Object>> files = (List<Map<String, Object>>) entries.getLast().get("attachments");
        assertEquals(attachment, files.getFirst().get("attachmentId"));
        assertEquals("shot.png", files.getFirst().get("name"));
    }

    @Test
    void aSharedImageDownloadsByItsShareIdAndAWithdrawnShareIsNotFound() {
        String[] token = new String[1];
        String punishment = appealedPlayer("e3".repeat(64), "pub-share", token);
        String staffFile = (String) multipart("/case/" + punishment + "/attachments", named(this.jwt.moderator()), png(), "capture.png")
                .json().get("attachmentId");
        assertEquals(200, post("/case/" + punishment + "/messages", Map.of("text", "see the capture", "attachmentIds", List.of(staffFile)),
                named(this.jwt.moderator())).status());
        Reply shared = post("/case/" + punishment + "/evidence-shares",
                Map.of("evidenceId", staffFile, "selection", List.of(), "confirmedMinimised", true), named(this.jwt.moderator()));
        assertEquals(200, shared.status(), shared.body());
        String shareId = (String) shared.json().get("shareId");

        assertEquals(200, download(DOWNLOAD + shareId, token[0]).status(), "the active share is downloadable");

        assertEquals(200, post("/case/" + punishment + "/evidence-shares/" + shareId + "/revoke", Map.of("reason", "OTHER"),
                named(this.jwt.moderator())).status());
        assertEquals(404, download(DOWNLOAD + shareId, token[0]).status(), "a withdrawn share is not found");
    }
}
