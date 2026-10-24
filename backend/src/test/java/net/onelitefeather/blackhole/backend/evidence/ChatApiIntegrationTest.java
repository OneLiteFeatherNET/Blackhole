package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staff side of the appeal chat over HTTP against the real containers: the upload route, its roles, sending a file in a message,
 * the evidence figures and the share routes. The virus scanner is switched off (it has its own tests).
 */
@Tag("integration")
class ChatApiIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    @TempDir
    Path files;

    private HttpClient http;

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "blackhole.evidence.files.storage", "local",
                "blackhole.evidence.files.local.path", this.files.toString(),
                "blackhole.evidence.scan.enabled", false
        );
    }

    @BeforeEach
    void client() {
        this.http = this.context.createBean(HttpClient.class, this.server.getURL());
    }

    @AfterEach
    void closeClient() {
        this.http.close();
    }

    private String givenPunishment(String owner, String templateKey) {
        post("/template", Map.of("key", templateKey, "reason", "Reason", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10))));
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", templateKey, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    private Reply upload(String punishment, byte[] content, String fileName, String token) {
        String boundary = "----chat-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        io.micronaut.http.MutableHttpRequest<byte[]> request = HttpRequest.POST("/case/" + punishment + "/attachments", body.toByteArray())
                .contentType("multipart/form-data; boundary=" + boundary);
        if (token != null) {
            request = request.bearerAuth(token);
        }
        HttpResponse<byte[]> response;
        try {
            response = this.http.toBlocking().exchange(request, byte[].class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked") HttpResponse<byte[]> failed = (HttpResponse<byte[]>) e.getResponse();
            response = failed;
        }
        String text = response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
        return new Reply(response.code(), text, this.context.getBean(io.micronaut.json.JsonMapper.class));
    }

    private static byte[] png() {
        try {
            return EvidenceFixtures.png(24, 16);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String discordBot() {
        return this.jwt.appOnly().claim("oid", UUID.randomUUID().toString()).roles(net.onelitefeather.blackhole.backend.security.TestJwtIssuer.DISCORD_ROLE).sign();
    }

    @Test
    void aModeratorStagesAFileAndItIsInvisibleUntilAMessageSendsIt() {
        String punishment = givenPunishment("c1".repeat(64), "chat-stage");

        Reply staged = upload(punishment, png(), "shot.png", this.jwt.moderator());

        assertEquals(200, staged.status(), staged.body());
        assertEquals("shot.png", staged.json().get("name"));
        assertEquals("image/png", staged.json().get("contentType"));
        assertEquals(24, ((Number) staged.json().get("width")).intValue());
        Reply listed = get("/punishment/" + punishment + "/evidence");
        assertEquals(0, ((List<?>) listed.json().get("items")).size(), "a staged file is in no listing");
        assertEquals(Map.of("count", 0, "playerSupplied", 0), get("/punishment/" + punishment + "/evidence/count").json());
        assertEquals(404, get("/evidence/" + staged.json().get("attachmentId")).status(), "and has no evidence route yet");
    }

    @Test
    void aMessageSendsTheFileAndItBecomesStaffEvidence() {
        String punishment = givenPunishment("c2".repeat(64), "chat-send");
        String attachmentId = (String) upload(punishment, png(), "shot.png", this.jwt.moderator()).json().get("attachmentId");

        Reply sent = post("/case/" + punishment + "/messages", Map.of("attachmentIds", List.of(attachmentId)), this.jwt.moderator());

        assertEquals(200, sent.status(), sent.body());
        Reply listed = get("/punishment/" + punishment + "/evidence");
        @SuppressWarnings("unchecked") List<Map<String, Object>> evidence = (List<Map<String, Object>>) listed.json().get("items");
        assertEquals(1, evidence.size());
        assertEquals("STAFF", evidence.getFirst().get("provenance"));
        assertEquals(attachmentId, evidence.getFirst().get("identifier"));
        assertEquals(Map.of("count", 1, "playerSupplied", 0), get("/punishment/" + punishment + "/evidence/count").json(), "a staff file is team evidence");
        Reply timeline = get("/case/" + punishment + "/timeline?types=message");
        assertTrue(timeline.body().contains(attachmentId), "the message names its file: " + timeline.body());
        assertTrue(!timeline.body().contains("file_key") && !timeline.body().toLowerCase().contains("sha256"), "no storage key or hash in the timeline");
    }

    @Test
    void aSecondMessageCannotSendTheSameFile() {
        String punishment = givenPunishment("c3".repeat(64), "chat-twice");
        String attachmentId = (String) upload(punishment, png(), "shot.png", this.jwt.moderator()).json().get("attachmentId");
        assertEquals(200, post("/case/" + punishment + "/messages", Map.of("attachmentIds", List.of(attachmentId)), this.jwt.moderator()).status());

        assertEquals(400, post("/case/" + punishment + "/messages", Map.of("text", "again", "attachmentIds", List.of(attachmentId)),
                this.jwt.moderator()).status());
    }

    @Test
    void theUploadRouteIsForModeratorsOnly() {
        String punishment = givenPunishment("c4".repeat(64), "chat-roles");

        assertEquals(403, upload(punishment, png(), "a.png", this.jwt.viewer()).status(), "viewer");
        assertEquals(403, upload(punishment, png(), "a.png", this.jwt.proxy()).status(), "proxy");
        assertEquals(403, upload(punishment, png(), "a.png", discordBot()).status(), "the Discord bot");
        assertEquals(401, upload(punishment, png(), "a.png", null).status(), "no token");
        assertEquals(200, upload(punishment, png(), "a.png", this.jwt.admin()).status(), "an admin may too");
    }

    @Test
    void anUnsupportedFileIsRefusedAndAnUnknownPunishmentIsNotFound() {
        String punishment = givenPunishment("c5".repeat(64), "chat-bad");

        assertEquals(415, upload(punishment, EvidenceFixtures.mp4(2048), "clip.mp4", this.jwt.moderator()).status());
        assertEquals(404, upload("no-such-punishment", png(), "a.png", this.jwt.moderator()).status());
    }

    @Test
    void theShareRoutesKeepTheirRoles() {
        String punishment = givenPunishment("c6".repeat(64), "chat-share");
        Map<String, Object> request = Map.of("evidenceId", UUID.randomUUID().toString(), "selection", List.of(0), "appellantSpeaker", "A",
                "confirmedMinimised", true);
        String base = "/case/" + punishment + "/evidence-shares";

        assertEquals(200, get(base, this.jwt.viewer()).status(), "viewer lists");
        assertEquals(403, post(base, request, this.jwt.viewer()).status(), "viewer cannot share");
        assertEquals(403, post(base, request, this.jwt.proxy()).status(), "proxy cannot share");
        assertEquals(403, get(base, this.jwt.proxy()).status(), "proxy cannot list");
        assertEquals(403, post(base + "/preview", request, this.jwt.proxy()).status(), "proxy cannot preview");
        assertEquals(403, post(base + "/" + UUID.randomUUID() + "/revoke", Map.of("reason", "OTHER"), this.jwt.viewer()).status(), "viewer cannot revoke");
        assertEquals(403, get(base, discordBot()).status(), "the bot cannot list");
        assertEquals(404, post(base, request, this.jwt.moderator()).status(), "a moderator may: the evidence is just unknown");
        assertEquals(404, post(base + "/preview", request, this.jwt.viewer()).status(), "a viewer may preview");
        assertEquals(404, post(base + "/" + UUID.randomUUID() + "/revoke", Map.of("reason", "OTHER"), this.jwt.moderator()).status());
    }

    @Test
    void aCaptureIsPreviewedRelabelledAndSharingNeedsAnAppeal() {
        String punishment = givenPunishment("c7".repeat(64), "chat-preview");
        Map<String, Object> capture = new HashMap<>();
        capture.put("punishment", punishment);
        capture.put("kind", "CHAT_LOG");
        capture.put("sourceSystem", "it");
        capture.put("source", STAFF);
        capture.put("payload", Map.of("lines", List.of(
                Map.of("timestamp", 1_000_000L, "speaker", "Alice", "text", "hello"),
                Map.of("timestamp", 1_004_000L, "speaker", "Bob", "text", "hi"))));
        Reply attached = post("/evidence", capture);
        assertEquals(200, attached.status(), attached.body());
        String evidenceId = (String) attached.json().get("identifier");
        String base = "/case/" + punishment + "/evidence-shares";
        Map<String, Object> request = Map.of("evidenceId", evidenceId, "selection", List.of(0, 1), "appellantSpeaker", "Alice", "confirmedMinimised", true);

        Reply preview = post(base + "/preview", request, this.jwt.viewer());

        assertEquals(200, preview.status(), preview.body());
        assertTrue(preview.body().contains("\"You\"") && preview.body().contains("\"Player 2\""), preview.body());
        assertTrue(!preview.body().contains("Alice") && !preview.body().contains("Bob"), "no original label: " + preview.body());
        Reply share = post(base, request, this.jwt.moderator());
        assertEquals(409, share.status(), "no appeal on this case: " + share.body());
        assertEquals("NO_APPEAL", share.json().get("code"));
        assertEquals(400, post(base, Map.of("evidenceId", evidenceId, "selection", List.of(0), "appellantSpeaker", "Alice"), this.jwt.moderator()).status(),
                "without confirmedMinimised");
    }
}
