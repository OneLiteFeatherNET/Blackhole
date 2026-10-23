package net.onelitefeather.blackhole.backend.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every write takes its actor from the token: a client-supplied actor field is ignored, the token's
 * {@code oid} is stored as the attributed actor and as {@code recordedByOid}, and {@code onBehalfOf}
 * works for the proxy only. Runs the real application against the real containers.
 */
@Tag("integration")
class VerifiedActorApiIntegrationTest extends ApiIntegrationTest {

    private static final UUID MODERATOR_OID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_MODERATOR_OID = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID PROXY_OID = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    private static final UUID SPOOFED = UUID.fromString("deadbeef-0000-4000-8000-000000000000");
    private static final UUID INGAME_STAFF = UUID.fromString("a0000000-0000-4000-8000-000000000004");

    @TempDir
    Path files;

    private HttpClient http;

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "blackhole.appeal.min-days-manual", 0,
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

    private String moderator() {
        return this.jwt.delegated().claim("oid", MODERATOR_OID.toString()).groups(TestJwtIssuer.MODERATOR_GROUP).sign();
    }

    private String otherModerator() {
        return this.jwt.delegated().claim("oid", OTHER_MODERATOR_OID.toString()).groups(TestJwtIssuer.MODERATOR_GROUP).sign();
    }

    private String proxy() {
        return this.jwt.appOnly().claim("oid", PROXY_OID.toString()).roles(TestJwtIssuer.PROXY_ROLE).sign();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private void givenTemplate(String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "Reason", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
    }

    private Reply apply(String owner, String template, Map<String, Object> extra, String token) {
        Map<String, Object> body = new HashMap<>(Map.of("owner", owner, "templateKey", template));
        body.putAll(extra);
        return post("/punishment/apply", body, token);
    }

    private static String owner(String seed) {
        return seed.repeat(64);
    }

    // --- punishment ---

    @Test
    void applyIgnoresASpoofedSourceAndStoresTheTokensOid() {
        givenTemplate("va-apply");

        Reply reply = apply(owner("b1"), "va-apply", Map.of("source", SPOOFED.toString()), moderator());

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> punishment = map(reply.json().get("punishment"));
        assertEquals(MODERATOR_OID.toString(), punishment.get("source"), "attributed to the token, not to the body");
        assertEquals(MODERATOR_OID.toString(), punishment.get("recordedByOid"));
        assertEquals(true, punishment.get("verified"));
    }

    @Test
    void applyByTheProxyWithOnBehalfOfAttributesTheInGameStaffAndRecordsTheProxy() {
        givenTemplate("va-proxy");

        Reply reply = apply(owner("b2"), "va-proxy", Map.of("onBehalfOf", INGAME_STAFF.toString()), proxy());

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> punishment = map(reply.json().get("punishment"));
        assertEquals(INGAME_STAFF.toString(), punishment.get("source"));
        assertEquals(PROXY_OID.toString(), punishment.get("recordedByOid"));
    }

    @Test
    void applyWithOnBehalfOfFromAModeratorIsRejected() {
        givenTemplate("va-reject");

        Reply reply = apply(owner("b3"), "va-reject", Map.of("onBehalfOf", INGAME_STAFF.toString()), moderator());

        assertEquals(400, reply.status(), reply.body());
        assertTrue(this.context.getBean(PunishmentProfileRepository.class).findById(owner("b3")).isEmpty(),
                "nothing was written for the rejected request");
    }

    @Test
    void revokeIgnoresASpoofedSourceAndRecordsTheTokensOid() {
        givenTemplate("va-revoke");
        String owner = owner("b4");
        apply(owner, "va-revoke", Map.of(), moderator());

        Reply reply = post("/punishment/revoke", Map.of("owner", owner, "section", "network", "source", SPOOFED.toString()), otherModerator());

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> metaData = map(map(reply.json().get("punishment")).get("metaData"));
        assertEquals(OTHER_MODERATOR_OID.toString(), metaData.get("revokedBy"));
        assertEquals(OTHER_MODERATOR_OID.toString(), metaData.get("recordedByOid"));
    }

    @Test
    void revokeWithOnBehalfOfFromAModeratorIsRejectedAndFromTheProxyAccepted() {
        givenTemplate("va-revoke-obo");
        String owner = owner("b5");
        apply(owner, "va-revoke-obo", Map.of(), moderator());

        assertEquals(400, post("/punishment/revoke", Map.of("owner", owner, "section", "network", "onBehalfOf", INGAME_STAFF.toString()),
                moderator()).status(), "a moderator may not vouch for anyone");
        Reply accepted = post("/punishment/revoke", Map.of("owner", owner, "section", "network", "onBehalfOf", INGAME_STAFF.toString()), proxy());

        assertEquals(200, accepted.status(), accepted.body());
        Map<String, Object> metaData = map(map(accepted.json().get("punishment")).get("metaData"));
        assertEquals(INGAME_STAFF.toString(), metaData.get("revokedBy"));
        assertEquals(PROXY_OID.toString(), metaData.get("recordedByOid"));
    }

    // --- report ---

    @Test
    void resolveReportIgnoresSpoofedResolvedByAndPunishmentSource() {
        Reply submitted = post("/report", Map.of("reporterHash", owner("c1"), "reportedHash", owner("c2"), "category", "CHEATING",
                "metaData", Map.of()), proxy());
        assertEquals(200, submitted.status(), submitted.body());
        String report = (String) submitted.json().get("identifier");

        Reply resolved = post("/report/" + report + "/resolve", Map.of("status", "DISMISSED", "resolvedBy", SPOOFED.toString(),
                "punishmentSource", SPOOFED.toString()), moderator());

        assertEquals(200, resolved.status(), resolved.body());
        assertEquals(MODERATOR_OID.toString(), resolved.json().get("resolvedBy"), "the resolver is the token's oid");
        assertEquals(MODERATOR_OID.toString(), resolved.json().get("recordedByOid"));
        assertEquals(true, resolved.json().get("verified"));
    }

    // --- appeal ---

    private String givenAppeal(String owner, String template, String issuerToken) {
        givenTemplate(template);
        Reply applied = apply(owner, template, Map.of(), issuerToken);
        String punishment = (String) map(applied.json().get("punishment")).get("identifier");
        Reply appeal = post("/appeal", Map.of("punishmentIdentifier", punishment, "appellantHash", owner, "statement", "please"), proxy());
        assertEquals(200, appeal.status(), appeal.body());
        assertEquals("ELIGIBLE_PENDING_REVIEW", appeal.json().get("status"), "precondition: the appeal awaits review: " + appeal.body());
        return (String) appeal.json().get("identifier");
    }

    @Test
    void reviewIgnoresASpoofedReviewerIdAndRecordsTheTokensOid() {
        String appeal = givenAppeal(owner("d1"), "va-appeal", moderator());

        Reply reply = post("/appeal/" + appeal + "/review", Map.of("decision", "DENIED", "reviewerId", SPOOFED.toString()), otherModerator());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(OTHER_MODERATOR_OID.toString(), reply.json().get("decidedBy"));
        assertEquals(OTHER_MODERATOR_OID.toString(), reply.json().get("recordedByOid"));
    }

    @Test
    void theIssuerCannotReviewTheirOwnPunishmentByNamingSomeoneElseAsReviewer() {
        String appeal = givenAppeal(owner("d2"), "va-selfreview", moderator());

        Reply reply = post("/appeal/" + appeal + "/review", Map.of("decision", "DENIED", "reviewerId", SPOOFED.toString()), moderator());

        assertEquals(403, reply.status(), "self-review is decided by the token's oid: " + reply.body());
    }

    // --- elo ---

    @Test
    void anEloAdjustmentIgnoresASpoofedSourceAndRecordsTheTokensOid() {
        String owner = owner("e1");

        Reply adjusted = post("/elo/" + owner + "/adjust", Map.of("section", "network", "delta", -10, "source", SPOOFED.toString()), moderator());

        assertEquals(200, adjusted.status(), adjusted.body());
        List<?> history = (List<?>) get("/elo/" + owner + "/history").json().get("content");
        Map<String, Object> metaData = map(map(history.getFirst()).get("metaData"));
        assertEquals(MODERATOR_OID.toString(), metaData.get("source"));
        assertEquals(MODERATOR_OID.toString(), metaData.get("recordedByOid"));
    }

    // --- evidence ---

    private Map<String, Object> link(String punishment, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("punishment", punishment, "kind", "LINK", "sourceSystem", "it",
                "reference", "https://example.org/replay/1"));
        body.putAll(extra);
        return body;
    }

    private String givenPunishment(String owner, String template) {
        givenTemplate(template);
        Reply applied = apply(owner, template, Map.of(), moderator());
        return (String) map(applied.json().get("punishment")).get("identifier");
    }

    @Test
    void evidenceAttachIgnoresASpoofedSourceAndRecordsTheTokensOid() {
        String punishment = givenPunishment(owner("f1"), "va-ev");

        Reply reply = post("/evidence", link(punishment, Map.of("source", SPOOFED.toString())), moderator());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(MODERATOR_OID.toString(), reply.json().get("createdBy"));
        assertEquals(MODERATOR_OID.toString(), reply.json().get("recordedByOid"));
    }

    @Test
    void evidenceOnBehalfOfIsRejectedForAModeratorAndAcceptedForTheProxy() {
        String punishment = givenPunishment(owner("f2"), "va-ev-obo");

        assertEquals(400, post("/evidence", link(punishment, Map.of("onBehalfOf", INGAME_STAFF.toString())), moderator()).status());
        Reply accepted = post("/evidence", link(punishment, Map.of("onBehalfOf", INGAME_STAFF.toString())), proxy());

        assertEquals(200, accepted.status(), accepted.body());
        assertEquals(INGAME_STAFF.toString(), accepted.json().get("createdBy"));
        assertEquals(PROXY_OID.toString(), accepted.json().get("recordedByOid"));
    }

    private Reply upload(String punishment, String token, Map<String, String> fields) {
        String boundary = "----actor-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Map<String, String> all = new HashMap<>(Map.of("punishment", punishment, "sourceSystem", "it"));
        all.putAll(fields);
        all.forEach((name, value) -> body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8)));
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shot.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        try {
            body.writeBytes(net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures.png(8, 8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        MutableHttpRequest<?> request = HttpRequest.POST("/evidence/file", body.toByteArray())
                .contentType("multipart/form-data; boundary=" + boundary).bearerAuth(token);
        HttpResponse<byte[]> response;
        try {
            response = this.http.toBlocking().exchange(request, byte[].class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked") HttpResponse<byte[]> failed = (HttpResponse<byte[]>) e.getResponse();
            response = failed;
        }
        String text = response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
        return new Reply(response.code(), text, this.context.getBean(JsonMapper.class));
    }

    @Test
    void anEvidenceUploadIgnoresASpoofedSourceFieldAndRecordsTheTokensOid() {
        String punishment = givenPunishment(owner("f3"), "va-ev-file");

        Reply reply = upload(punishment, moderator(), Map.of("source", SPOOFED.toString()));

        assertEquals(200, reply.status(), reply.body());
        assertEquals(MODERATOR_OID.toString(), reply.json().get("createdBy"));
        assertEquals(MODERATOR_OID.toString(), reply.json().get("recordedByOid"));
    }

    @Test
    void anEvidenceUploadWithOnBehalfOfIsRejectedForAModerator() {
        String punishment = givenPunishment(owner("f4"), "va-ev-file-obo");

        assertEquals(400, upload(punishment, moderator(), Map.of("onBehalfOf", INGAME_STAFF.toString())).status());
        assertEquals(200, upload(punishment, proxy(), Map.of("onBehalfOf", INGAME_STAFF.toString())).status());
    }

    // --- staff actor ---

    @Test
    void aFirstWriteCreatesTheStaffActorRowWithOnlyOidKindAndName() {
        givenTemplate("va-staff");

        apply(owner("a1"), "va-staff", Map.of(), moderator());
        apply(owner("a2"), "va-staff", Map.of("onBehalfOf", INGAME_STAFF.toString()), proxy());

        StaffActorRepository actors = this.context.getBean(StaffActorRepository.class);
        StaffActorEntity person = actors.findById(MODERATOR_OID).orElseThrow();
        assertEquals(ActorKind.HUMAN, person.getKind());
        assertEquals("Test User", person.getDisplayName());
        assertEquals(ActorKind.SERVICE, actors.findById(PROXY_OID).orElseThrow().getKind());
        assertTrue(actors.findById(INGAME_STAFF).isEmpty(), "a delegated in-game UUID is not a principal and gets no row");
    }

    @Test
    void anAdminAnonymizesAStaffActorAndHistoryStillReferencesTheOid() {
        givenTemplate("va-anon");
        Reply applied = apply(owner("a3"), "va-anon", Map.of(), moderator());
        assertEquals(200, applied.status());

        Reply forbidden = post("/admin/staff-actor/" + MODERATOR_OID + "/anonymize", null, moderator());
        Reply done = post("/admin/staff-actor/" + MODERATOR_OID + "/anonymize", null, this.jwt.admin());

        assertEquals(403, forbidden.status(), "a moderator may not anonymize");
        assertEquals(200, done.status(), done.body());
        StaffActorEntity row = this.context.getBean(StaffActorRepository.class).findById(MODERATOR_OID).orElseThrow();
        assertNull(row.getDisplayName(), "the name is cleared");
        Map<String, Object> punishment = map(map(get("/profile/" + owner("a3")).json().get("active")).get("network"));
        assertEquals(MODERATOR_OID.toString(), punishment.get("recordedByOid"), "history still references the oid");
        assertNotNull(row.getOid());
    }

    @Test
    void anonymizingAnUnknownStaffActorIsNotFound() {
        assertEquals(404, post("/admin/staff-actor/" + UUID.randomUUID() + "/anonymize", null, this.jwt.admin()).status());
    }
}
