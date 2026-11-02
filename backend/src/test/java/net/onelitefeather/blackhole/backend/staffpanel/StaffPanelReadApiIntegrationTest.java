package net.onelitefeather.blackhole.backend.staffpanel;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.exemption.ExemptionReviewService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seven read-by-id routes of the staff panel over HTTP, each with a real fixture: the 200 body, the 404 and 400 answers,
 * the forbidden fields (no Ban ID, no IPv4 address, no name) and the equality with the list item of the same entity.
 * The role rules of the same routes are in {@code PermissionMatrixIntegrationTest}.
 */
@Tag("integration")
class StaffPanelReadApiIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();
    private static final String UNKNOWN = UUID.randomUUID().toString();
    private static final String NOT_A_UUID = "not-a-uuid";
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.appeal.min-days-manual", 0);
    }

    private String viewer() {
        return this.jwt.viewer();
    }

    private String moderator() {
        return this.jwt.moderator();
    }

    private String admin() {
        return this.jwt.admin();
    }

    /** Punishes {@code owner} with a one day template; returns the punishment identifier. */
    private String punish(String owner, String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "Spamming the chat", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", key, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    private String banIdOf(String punishment) {
        return (String) get("/punishment/" + punishment + "/ban-id", moderator()).json().get("banId");
    }

    /** The JSON body must not contain the Ban ID of the fixture, nor an IPv4 address; the body is checked as text. */
    private void assertNoForbiddenField(Reply reply, String banId) {
        assertFalse(reply.body().contains(banId), "a response must not carry a Ban ID: " + reply.body());
        assertFalse(IPV4.matcher(reply.body()).find(), "a response must not carry an IP address: " + reply.body());
    }

    private static Map<String, Object> itemWith(List<Map<String, Object>> items, String key, Object value) {
        return items.stream().filter(item -> value.equals(item.get(key))).findFirst()
                .orElseThrow(() -> new AssertionError("no list item with " + key + "=" + value + " in " + items));
    }

    // ---- GET /appeal/{identifier} ----

    @Test
    void theAppealReadAnswersTheAppealOfItsPunishmentWithoutItsBanId() {
        String owner = "a1".repeat(64);
        String punishment = punish(owner, "read-appeal");
        Reply appeal = post("/appeal", Map.of("punishmentIdentifier", punishment, "appellantHash", owner, "statement", "please"),
                this.jwt.proxy());
        assertEquals(200, appeal.status(), appeal.body());
        String identifier = (String) appeal.json().get("identifier");

        Reply read = get("/appeal/" + identifier, viewer());

        assertEquals(200, read.status(), read.body());
        assertEquals(identifier, read.json().get("identifier"));
        assertEquals(owner, read.json().get("appellantHash"));
        assertNoForbiddenField(read, banIdOf(punishment));
    }

    @Test
    void aMalformedAppealIdentifierIsBadRequest() {
        assertEquals(400, get("/appeal/" + NOT_A_UUID, viewer()).status(), "the identifier is a UUID");
    }

    // ---- GET /exemption/reviews/{id} ----

    @Test
    void theExemptionReviewReadAnswersTheReviewTheListAnswers() {
        String owner = "b2".repeat(64);
        this.context.getBean(ExemptionReviewService.class).open(owner, "network", "SOFT", 3);
        List<Map<String, Object>> list = (List<Map<String, Object>>) (Object) get("/exemption/reviews", admin()).json().get("items");
        Map<String, Object> listed = itemWith(list, "owner", owner);
        String id = (String) listed.get("id");

        Reply read = get("/exemption/reviews/" + id, admin());

        assertEquals(200, read.status(), read.body());
        assertEquals(listed, read.json().get("review"), "the read answers the same review the list does");
        assertFalse(IPV4.matcher(read.body()).find(), "a review carries no address");
    }

    @Test
    void anUnknownExemptionReviewIsNotFoundForAnAdmin() {
        Reply reply = get("/exemption/reviews/" + UNKNOWN, admin());

        assertEquals(404, reply.status(), reply.body());
        assertEquals("NOT_FOUND", reply.json().get("kind"), reply.body());
    }

    @Test
    void aMalformedExemptionReviewIdentifierIsBadRequest() {
        assertEquals(400, get("/exemption/reviews/" + NOT_A_UUID, admin()).status());
    }

    // ---- GET /area-bans/{id} ----

    @Test
    void theAreaBanReadAnswersTheBanTheListAnswers() {
        Reply created = post("/area-bans", Map.of("kind", "ASN", "value", "4200000042", "reason", "read test", "durationSeconds", 3600), moderator());
        assertEquals(200, created.status(), created.body());
        String id = (String) created.json().get("id");

        Reply read = get("/area-bans/" + id, viewer());

        assertEquals(200, read.status(), read.body());
        List<Map<String, Object>> bans = (List<Map<String, Object>>) (Object) get("/area-bans?includeInactive=true", viewer()).json()
                .get("bans");
        assertEquals(itemWith(bans, "id", id), read.json(), "the read answers the same area ban the list does");
        assertFalse(IPV4.matcher(read.body()).find(), "an area ban carries no address");
    }

    @Test
    void aMalformedAreaBanIdentifierIsBadRequest() {
        assertEquals(400, get("/area-bans/" + NOT_A_UUID, viewer()).status(), "an area ban id is a UUID");
    }

    @Test
    void anUnknownAreaBanIsNotFound() {
        Reply reply = get("/area-bans/" + UNKNOWN, viewer());

        assertEquals(404, reply.status(), reply.body());
        assertEquals("NOT_FOUND", reply.json().get("kind"));
    }

    // ---- GET /intel/allow-list/{owner} ----

    @Test
    void theAllowListReadAnswersTheEntryTheListAnswers() {
        String owner = "d4".repeat(64);
        assertEquals(200, post("/intel/allow-list", Map.of("owner", owner, "reason", "read test", "durationDays", 3), moderator()).status());

        Reply read = get("/intel/allow-list/" + owner, viewer());

        assertEquals(200, read.status(), read.body());
        List<Map<String, Object>> entries = (List<Map<String, Object>>) (Object) get("/intel/allow-list", viewer()).json().get("entries");
        assertEquals(itemWith(entries, "owner", owner), read.json(), "the read answers the same exemption the list does");
        assertTrue(read.json().get("actorOid") != null, "the entry names the staff object id that added it");
    }

    @Test
    void anAllowListOwnerThatIsNotAHashIsBadRequestBeforeAnyLookup() {
        assertEquals(400, get("/intel/allow-list/Steve", viewer()).status(), "a name");
        assertEquals(400, get("/intel/allow-list/" + UUID.randomUUID(), viewer()).status(), "a raw player UUID");
        assertEquals(400, get("/intel/allow-list/" + "ab".repeat(63) + "a", viewer()).status(), "127 characters");
    }

    @Test
    void anUnknownAllowListOwnerIsNotFound() {
        Reply reply = get("/intel/allow-list/" + "ee".repeat(64), viewer());

        assertEquals(404, reply.status(), reply.body());
        assertEquals("NOT_FOUND", reply.json().get("kind"));
    }

    // ---- GET /admin/audit/{auditId} ----

    @Test
    void theAuditReadAnswersTheEntryTheListAnswers() {
        assertEquals(200, post("/intel/allow-list", Map.of("owner", "e5".repeat(64), "reason", "audit fixture", "durationDays", 1),
                moderator()).status());
        List<Map<String, Object>> items = (List<Map<String, Object>>) (Object) get("/admin/audit?limit=1", admin()).json().get("items");
        assertFalse(items.isEmpty(), "the allow-list add is audited");
        String auditId = (String) items.getFirst().get("auditId");

        Reply read = get("/admin/audit/" + auditId, admin());

        assertEquals(200, read.status(), read.body());
        assertEquals(items.getFirst(), read.json(), "the read answers the same audit entry the list does");
    }

    @Test
    void anUnknownAuditEntryIsNotFoundForAnAdmin() {
        Reply reply = get("/admin/audit/" + UNKNOWN, admin());

        assertEquals(404, reply.status(), reply.body());
        assertEquals("NOT_FOUND", reply.json().get("kind"));
    }

    @Test
    void aMalformedAuditIdentifierIsBadRequest() {
        assertEquals(400, get("/admin/audit/" + NOT_A_UUID, admin()).status());
    }

    // ---- GET /admin/staff-actor/{oid} ----

    @Test
    void theStaffActorReadAnswersTheActorTheListAnswers() {
        assertEquals(200, get("/admin/staff-actor", admin()).status());
        List<Map<String, Object>> items = (List<Map<String, Object>>) (Object) get("/admin/staff-actor", admin()).json().get("items");
        assertFalse(items.isEmpty(), "the verified requests of this class are known staff actors");
        String oid = (String) items.getFirst().get("oid");

        Reply read = get("/admin/staff-actor/" + oid, admin());

        assertEquals(200, read.status(), read.body());
        assertEquals(items.getFirst(), read.json(), "the read answers the same staff actor the list does");
        assertFalse(IPV4.matcher(read.body()).find(), "no address");
    }

    @Test
    void anUnknownStaffActorIsNotFoundForAnAdmin() {
        Reply reply = get("/admin/staff-actor/" + UNKNOWN, admin());

        assertEquals(404, reply.status(), reply.body());
        assertEquals("NOT_FOUND", reply.json().get("kind"));
    }

    @Test
    void aMalformedStaffActorIdentifierIsBadRequest() {
        assertEquals(400, get("/admin/staff-actor/" + NOT_A_UUID, admin()).status());
    }

    // ---- GET /appeal-conditions/versions/{version} ----

    @Test
    void theTermsVersionReadAnswersTheSeededVersionOne() {
        Reply read = get("/appeal-conditions/versions/1", viewer());

        assertEquals(200, read.status(), read.body());
        assertEquals(1, read.json().get("version"));
        assertNotNull(read.json().get("text"), "the text of the version is in the body");
        assertFalse(read.body().contains("createdBy"), "a version names no staff member");
    }

    @Test
    void anUnknownTermsVersionIsNotFound() {
        Reply reply = get("/appeal-conditions/versions/999999", viewer());

        assertEquals(404, reply.status(), reply.body());
        assertEquals("NOT_FOUND", reply.json().get("kind"));
    }

    @Test
    void aTermsVersionBelowOneIsBadRequest() {
        assertEquals(400, get("/appeal-conditions/versions/0", viewer()).status(), "versions start at 1");
    }
}
