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

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence endpoints over HTTP against the real containers, with files on a temporary
 * directory and the virus scanner switched off (the scanner has its own tests).
 */
@Tag("integration")
class EvidenceApiIntegrationTest extends ApiIntegrationTest {

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

    private HttpResponse<byte[]> exchange(HttpRequest<?> request) {
        try {
            return this.http.toBlocking().exchange(
                    ((io.micronaut.http.MutableHttpRequest<?>) request).bearerAuth(this.jwt.admin()), byte[].class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked") HttpResponse<byte[]> response = (HttpResponse<byte[]>) e.getResponse();
            return response;
        }
    }

    private static String text(HttpResponse<byte[]> response) {
        return response.body() == null ? "" : new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String givenPunishment(String owner, String templateKey) {
        post("/template", Map.of("key", templateKey, "reason", "Reason", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10))));
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", templateKey, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    private Reply attachLink(String punishment) {
        Map<String, Object> body = new HashMap<>();
        body.put("punishment", punishment);
        body.put("kind", "LINK");
        body.put("sourceSystem", "it");
        body.put("reference", "https://example.org/replay/1");
        body.put("source", STAFF);
        return post("/evidence", body);
    }

    /** One text field of a multipart form; the JDK HTTP client cannot build multipart bodies itself. */
    private static void field(java.io.ByteArrayOutputStream body, String boundary, String name, String value) {
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Reply upload(String punishment, byte[] png) {
        String boundary = "----evidence-" + UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        field(body, boundary, "punishment", punishment);
        field(body, boundary, "sourceSystem", "it");
        field(body, boundary, "source", STAFF);
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shot.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        body.writeBytes(png);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        HttpResponse<byte[]> response = exchange(HttpRequest.POST("/evidence/file", body.toByteArray())
                .contentType("multipart/form-data; boundary=" + boundary));
        return new Reply(response.code(), text(response), this.context.getBean(io.micronaut.json.JsonMapper.class));
    }

    @Test
    void attachedLinkEvidenceIsListedAndCounted() {
        String punishment = givenPunishment("e1".repeat(64), "ev-link");

        Reply attached = attachLink(punishment);
        assertEquals(200, attached.status(), attached.body());
        assertEquals("LINK", attached.json().get("kind"));

        Reply listed = get("/punishment/" + punishment + "/evidence");
        assertEquals(200, listed.status(), listed.body());
        assertEquals(1, ((List<?>) listed.json().get("items")).size());
        Reply count = get("/punishment/" + punishment + "/evidence/count");
        assertEquals(Map.of("count", 1), count.json(), "the count endpoint answers the count and nothing else");
    }

    @Test
    void evidenceCanBeAttachedAndListedByShortId() {
        String owner = "e2".repeat(64);
        String punishment = givenPunishment(owner, "ev-short");
        Reply applied = get("/punishment/" + punishment + "/evidence/count");
        assertEquals(200, applied.status());
        String shortId = net.onelitefeather.blackhole.backend.punishment.PunishmentShortId.display(
                net.onelitefeather.blackhole.backend.punishment.PunishmentShortId.keyOf(punishment).orElseThrow());

        Map<String, Object> body = new HashMap<>(Map.of("punishment", shortId, "kind", "LINK", "sourceSystem", "it",
                "reference", "https://example.org/x"));
        assertEquals(200, post("/evidence", body).status());

        Reply listed = get("/punishment/%23" + shortId.substring(1) + "/evidence");
        assertEquals(200, listed.status(), listed.body());
        assertEquals(1, ((List<?>) listed.json().get("items")).size());
    }

    @Test
    void anUnknownPunishmentIsNotFound() {
        assertEquals(404, get("/punishment/does-not-exist/evidence/count").status());
    }

    @Test
    void evidenceByOwnerIsListed() {
        String owner = "e3".repeat(64);
        String punishment = givenPunishment(owner, "ev-owner");
        attachLink(punishment);

        Reply listed = get("/evidence?owner=" + owner);

        assertEquals(200, listed.status(), listed.body());
        assertEquals(1, ((List<?>) listed.json().get("items")).size());
    }

    @Test
    void anUploadedImageIsStoredAndServedWithTheSafetyHeaders() throws IOException {
        String punishment = givenPunishment("e4".repeat(64), "ev-file");
        byte[] png = EvidenceFixtures.png(16, 16);

        Reply uploaded = upload(punishment, png);
        assertEquals(200, uploaded.status(), uploaded.body());
        String id = (String) uploaded.json().get("identifier");
        @SuppressWarnings("unchecked") Map<String, Object> file = (Map<String, Object>) uploaded.json().get("file");
        assertEquals("image/png", file.get("contentType"));

        HttpResponse<byte[]> content = exchange(HttpRequest.GET("/evidence/" + id + "/content"));

        assertEquals(200, content.code());
        assertEquals(((Number) file.get("size")).intValue(), content.body().length);
        assertEquals("image/png", content.header("Content-Type"));
        assertEquals("nosniff", content.header("X-Content-Type-Options"));
        assertEquals("sandbox", content.header("Content-Security-Policy"));
        assertEquals("private, no-store", content.header("Cache-Control"));
        assertEquals("bytes", content.header("Accept-Ranges"));
        assertTrue(content.header("Content-Disposition").startsWith("inline"), content.header("Content-Disposition"));
        assertNull(content.header("Content-Range"));
    }

    @Test
    void aRangeRequestIsAnsweredWith206AndExactlyThoseBytes() throws IOException {
        String punishment = givenPunishment("e5".repeat(64), "ev-range");
        String id = (String) upload(punishment, EvidenceFixtures.png(32, 32)).json().get("identifier");
        byte[] whole = exchange(HttpRequest.GET("/evidence/" + id + "/content")).body();

        HttpResponse<byte[]> part = exchange(HttpRequest.GET("/evidence/" + id + "/content").header("Range", "bytes=0-9"));

        assertEquals(206, part.code());
        assertEquals("bytes 0-9/" + whole.length, part.header("Content-Range"));
        assertArrayEquals(java.util.Arrays.copyOfRange(whole, 0, 10), part.body());
    }

    @Test
    void aRangeBehindTheEndIsAnsweredWith416() throws IOException {
        String punishment = givenPunishment("e6".repeat(64), "ev-416");
        String id = (String) upload(punishment, EvidenceFixtures.png(8, 8)).json().get("identifier");

        HttpResponse<byte[]> response = exchange(HttpRequest.GET("/evidence/" + id + "/content").header("Range", "bytes=999999-"));

        assertEquals(416, response.code());
        assertNotNull(response.header("Content-Range"));
    }

    @Test
    void aSpoofedUploadIsRejectedWith415() {
        String punishment = givenPunishment("e7".repeat(64), "ev-spoof");

        Reply rejected = upload(punishment, "MZ this is not a png".getBytes());

        assertEquals(415, rejected.status(), rejected.body());
    }

    @Test
    void deletingWithoutAReasonIs400AndKeepsTheEvidence() {
        String punishment = givenPunishment("e8".repeat(64), "ev-del");
        String id = (String) attachLink(punishment).json().get("identifier");

        HttpResponse<byte[]> rejected = exchange(HttpRequest.DELETE("/evidence/" + id));

        assertEquals(400, rejected.code());
        assertEquals(200, get("/evidence/" + id).status(), "the item still exists");
    }

    @Test
    void deletingWithAReasonRemovesTheEvidenceAndItsFile() throws IOException {
        String punishment = givenPunishment("e9".repeat(64), "ev-del2");
        String id = (String) upload(punishment, EvidenceFixtures.png(8, 8)).json().get("identifier");

        HttpResponse<byte[]> deleted = exchange(HttpRequest.DELETE("/evidence/" + id + "?reason=WRONG_EVIDENCE&note=typo&source=" + STAFF));

        assertEquals(200, deleted.code(), text(deleted));
        assertEquals(404, get("/evidence/" + id).status());
        try (var walk = java.nio.file.Files.walk(this.files)) {
            assertEquals(0, walk.filter(java.nio.file.Files::isRegularFile).count(), "the stored file is gone");
        }
    }

    @Test
    void erasingAnOwnerRemovesAllTheirEvidence() {
        String owner = "f1".repeat(64);
        String punishment = givenPunishment(owner, "ev-erase");
        attachLink(punishment);
        attachLink(punishment);

        HttpResponse<byte[]> erased = exchange(HttpRequest.DELETE("/evidence/owner/" + owner + "?reason=ERASURE_REQUEST"));

        assertEquals(200, erased.code(), text(erased));
        assertTrue(text(erased).contains("\"deleted\":2"), text(erased));
        assertEquals(0, ((List<?>) get("/evidence?owner=" + owner).json().get("items")).size());
    }

    @Test
    void anAppealCarriesOnlyTheEvidenceCount() {
        String owner = "f2".repeat(64);
        String punishment = givenPunishment(owner, "ev-appeal");
        attachLink(punishment);
        attachLink(punishment);

        Reply appeal = post("/appeal", Map.of("punishmentIdentifier", punishment, "appellantHash", owner, "statement", "please"));

        assertEquals(200, appeal.status(), appeal.body());
        assertEquals(2, appeal.json().get("evidenceCount"));
        assertTrue(appeal.json().keySet().stream().filter(key -> key.toLowerCase().contains("evidence")).allMatch("evidenceCount"::equals),
                "no other evidence field: " + appeal.body());
    }

    @Test
    void thePunishmentListingCarriesOnlyTheEvidenceCount() {
        String owner = "f3".repeat(64);
        String punishment = givenPunishment(owner, "ev-listing");
        attachLink(punishment);

        Reply listed = get("/punishment/short/" + net.onelitefeather.blackhole.backend.punishment.PunishmentShortId.keyOf(punishment).orElseThrow());

        assertEquals(200, listed.status(), listed.body());
        Map<String, Object> entry = listed.jsonList().stream().filter(e -> punishment.equals(e.get("identifier"))).findFirst().orElseThrow();
        assertEquals(1, entry.get("evidenceCount"));
    }

    @Test
    void aDownloadIsAuditedWithTheSuppliedActor() throws IOException {
        String punishment = givenPunishment("f4".repeat(64), "ev-audit");
        String id = (String) upload(punishment, EvidenceFixtures.png(8, 8)).json().get("identifier");

        assertEquals(200, exchange(HttpRequest.GET("/evidence/" + id + "/content?source=" + STAFF)).code());

        List<EvidenceAuditEntity> trail = this.context.getBean(EvidenceAuditRepository.class).findByEvidenceIdentifierOrderByOccurredAt(UUID.fromString(id));
        assertTrue(trail.stream().anyMatch(entry -> entry.getAction() == EvidenceAuditAction.DOWNLOADED && UUID.fromString(STAFF).equals(entry.getActor())),
                "a DOWNLOADED entry with the actor exists: " + trail.stream().map(EvidenceAuditEntity::getAction).toList());
    }
}
