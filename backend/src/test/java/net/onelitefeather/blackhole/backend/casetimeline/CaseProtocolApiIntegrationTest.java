package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision stores a protocol, staff list, open and download it, a redaction purges and regenerates it:
 * the whole path over HTTP against the real application, PostgreSQL and a local file store.
 */
@Tag("integration")
class CaseProtocolApiIntegrationTest extends ApiIntegrationTest {

    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000021");
    private static final UUID REVIEWER = UUID.fromString("b0b00000-0000-4000-8000-000000000022");
    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    @TempDir
    Path files;

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "blackhole.evidence.files.storage", "local",
                "blackhole.evidence.files.local.path", this.files.toString(),
                "blackhole.evidence.scan.enabled", false,
                "blackhole.protocol.reconcile-interval", "0"
        );
    }

    private String moderator(UUID oid) {
        return named(this.jwt.delegated().groups(TestJwtIssuer.MODERATOR_GROUP).claim("oid", oid.toString()).claim("name", "Staff Alice").sign());
    }

    private String viewer() {
        return this.jwt.viewer();
    }

    private String applyPunishment(String owner, String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "r", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", key, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    private UUID fileAppeal(String punishment, String owner) throws Exception {
        UUID id = UUID.randomUUID();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO appeals (identifier, appellant_hash, statement, created_at, status, "
                     + "eligibility_check_result, meta_data, updated_at, punishment_identifier) VALUES (?, ?, 'please', ?, 1, "
                     + "'{\"severityTier\":\"STANDARD\"}'::jsonb, '{}'::jsonb, ?, ?)")) {
            long now = System.currentTimeMillis();
            statement.setObject(1, id);
            statement.setString(2, owner);
            statement.setLong(3, now - 10_000);
            statement.setLong(4, now - 10_000);
            statement.setString(5, punishment);
            statement.executeUpdate();
        }
        return id;
    }

    private long auditCount(String evidenceId, int action) throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM evidence_audit WHERE evidence_identifier = ? AND action = ?")) {
            statement.setObject(1, UUID.fromString(evidenceId));
            statement.setInt(2, action);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> protocols(String punishment) {
        Reply reply = get("/case/" + punishment + "/protocol", viewer());
        assertEquals(200, reply.status(), reply.body());
        return (List<Map<String, Object>>) reply.json().get("items");
    }

    private String say(String punishment, String text) {
        Reply reply = post("/case/" + punishment + "/messages", Map.of("text", text), moderator(ALICE));
        assertEquals(200, reply.status(), reply.body());
        return (String) reply.json().get("messageId");
    }

    private String decideDenied(UUID appeal) {
        Reply decided = post("/appeal/" + appeal + "/review", Map.of("decision", "DENIED", "decisionNote", "internal note about the ban"), moderator(REVIEWER));
        assertEquals(200, decided.status(), decided.body());
        return decided.body();
    }

    @Test
    void decidingAnAppealStoresProtocolVersionOneThatStaffCanListOpenAndDownload() throws Exception {
        String owner = "f1".repeat(64);
        String punishment = applyPunishment(owner, "proto-decide");
        UUID appeal = fileAppeal(punishment, owner);
        say(punishment, "Please tell us what happened.");

        decideDenied(appeal);

        List<Map<String, Object>> listed = protocols(punishment);
        assertEquals(1, listed.size());
        Map<String, Object> version = listed.getFirst();
        assertEquals(1, ((Number) version.get("version")).intValue());
        assertEquals(true, version.get("current"));
        String evidenceId = (String) version.get("evidenceId");

        Reply opened = get("/case/" + punishment + "/protocol/" + evidenceId, viewer());
        assertEquals(200, opened.status(), opened.body());
        String text = (String) opened.json().get("text");
        assertTrue(text.contains("Please tell us what happened."), text);
        assertTrue(text.contains("DENIED"), text);
        assertFalse(text.contains("internal note about the ban"), "the decision note stays out of the conversation protocol");
        assertFalse(text.contains(owner), "no owner hash");

        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            HttpResponse<String> download = client.toBlocking().exchange(
                    HttpRequest.GET("/case/" + punishment + "/protocol/" + evidenceId + "/download?format=json").bearerAuth(viewer()), String.class);
            assertEquals(200, download.code());
            assertEquals("nosniff", download.header("X-Content-Type-Options"));
            assertEquals("sandbox", download.header("Content-Security-Policy"));
            assertEquals("private, no-store", download.header("Cache-Control"));
            assertTrue(download.header("Content-Disposition").startsWith("attachment; filename=\""), download.header("Content-Disposition"));
            assertTrue(download.header("Content-Type").startsWith("application/json"), download.header("Content-Type"));
            assertTrue(download.body().contains("\"schemaVersion\" : 2"));
            assertEquals(400, get("/case/" + punishment + "/protocol/" + evidenceId + "/download?format=pdf", viewer()).status());
        }
    }

    @Test
    void theProtocolIsEvidenceOfKindTranscriptThatIsNotCountedAndIsListed() throws Exception {
        String owner = "f2".repeat(64);
        String punishment = applyPunishment(owner, "proto-evidence");
        UUID appeal = fileAppeal(punishment, owner);
        decideDenied(appeal);

        Reply evidence = get("/punishment/" + punishment + "/evidence", viewer());
        assertEquals(200, evidence.status(), evidence.body());
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) evidence.json().get("items");
        assertEquals(1, items.size());
        assertEquals("TRANSCRIPT", items.getFirst().get("kind"));
        assertEquals("VALID", items.getFirst().get("status"));
        assertEquals(0, ((Number) get("/punishment/" + punishment + "/evidence/count", viewer()).json().get("count")).intValue());

        Reply attach = post("/evidence", Map.of("punishment", punishment, "kind", "TRANSCRIPT", "sourceSystem", "client", "payload", Map.of("a", "b")),
                named(this.jwt.moderator()));
        assertEquals(400, attach.status(), attach.body());
    }

    @Test
    void openingAndDownloadingAreAuditedAndAProtocolOfAnotherCaseIsNotFound() throws Exception {
        String owner = "f3".repeat(64);
        String punishment = applyPunishment(owner, "proto-audit");
        UUID appeal = fileAppeal(punishment, owner);
        decideDenied(appeal);
        String evidenceId = (String) protocols(punishment).getFirst().get("evidenceId");

        get("/case/" + punishment + "/protocol/" + evidenceId, viewer());
        get("/case/" + punishment + "/protocol/" + evidenceId + "/download", viewer());

        assertEquals(1, auditCount(evidenceId, 1), "VIEWED");
        assertEquals(1, auditCount(evidenceId, 3), "DOWNLOADED");
        String other = applyPunishment("f4".repeat(64), "proto-other");
        assertEquals(404, get("/case/" + other + "/protocol/" + evidenceId, viewer()).status(), "another case's protocol is not served");
    }

    @Test
    void redactingAMessageMakesTheOldProtocolUnreadableAndRegeneratesIt() throws Exception {
        String owner = "f5".repeat(64);
        String punishment = applyPunishment(owner, "proto-redact");
        UUID appeal = fileAppeal(punishment, owner);
        String secret = say(punishment, "my secret street name");
        decideDenied(appeal);
        String oldEvidence = (String) protocols(punishment).getFirst().get("evidenceId");

        Reply redacted = post("/case/" + punishment + "/messages/" + secret + "/redact", Map.of("reason", "OTHER"), named(this.jwt.admin()));
        assertEquals(200, redacted.status(), redacted.body());

        assertEquals(404, get("/case/" + punishment + "/protocol/" + oldEvidence, viewer()).status(), "the old text is gone");
        assertEquals(404, get("/evidence/" + oldEvidence, viewer()).status(), "also through the evidence route");
        assertEquals(404, get("/evidence/" + oldEvidence + "/content", viewer()).status());
        List<Map<String, Object>> after = protocols(punishment);
        assertEquals(1, after.size(), "only the regenerated version is listed");
        assertEquals(2, ((Number) after.getFirst().get("version")).intValue(), "numbers are never reused");
        String text = (String) get("/case/" + punishment + "/protocol/" + after.getFirst().get("evidenceId"), viewer()).json().get("text");
        assertFalse(text.contains("secret street"), text);
        assertTrue(text.contains("[removed for data protection]"), text);
    }

    @Test
    void aCaseWithoutAnyProtocolListsNothing() {
        String punishment = applyPunishment("f6".repeat(64), "proto-none");

        assertTrue(protocols(punishment).isEmpty());
    }
}
