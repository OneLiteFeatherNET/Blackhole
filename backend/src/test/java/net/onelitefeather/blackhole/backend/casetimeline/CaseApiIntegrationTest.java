package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The case routes over HTTP with self-signed tokens per role, against the real application and database. */
@Tag("integration")
class CaseApiIntegrationTest extends ApiIntegrationTest {

    private static final String OWNER = "c4".repeat(64);
    private static final UUID ALICE = UUID.fromString("a11ce000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("b0b00000-0000-4000-8000-000000000002");

    private String moderator(UUID oid) {
        return this.jwt.delegated().groups(TestJwtIssuer.MODERATOR_GROUP).claim("oid", oid.toString()).claim("name", "Staff " + oid.toString().substring(0, 4)).sign();
    }

    private String admin(UUID oid) {
        return this.jwt.delegated().groups(TestJwtIssuer.ADMIN_GROUP).claim("oid", oid.toString()).sign();
    }

    private String givenPunishment() {
        String id = UUID.randomUUID().toString();
        Map<String, Object> meta = new HashMap<>();
        meta.put("creation_date", 1L);
        meta.put("update_date", 1L);
        this.context.getBean(PunishmentRepository.class).save(new PunishmentEntity(id, UUID.randomUUID(), OWNER, "network", false, 1L, null, meta));
        return id;
    }

    private String addNote(String punishment, String token, String text) {
        Reply reply = post("/case/" + punishment + "/notes", Map.of("text", text), token);
        assertEquals(200, reply.status(), reply.body());
        return (String) reply.json().get("noteId");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> timeline(String punishment) {
        return (List<Map<String, Object>>) get("/case/" + punishment + "/timeline").json().get("items");
    }

    @Test
    void aViewerReadsTheDefaultCaseOfAPunishmentWithoutARow() {
        String id = givenPunishment();

        Reply reply = get("/case/" + id, this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertEquals("OPEN", reply.json().get("status"));
        assertEquals("NORMAL", reply.json().get("priority"));
        assertEquals(0, ((Number) reply.json().get("version")).intValue());
        assertTrue(timeline(id).isEmpty(), "no events yet");
    }

    @Test
    void aModeratorChangesTheStatusAndTheTimelineShowsTheVerifiedActor() {
        String id = givenPunishment();

        Reply changed = patch("/case/" + id, Map.of("expectedVersion", 0, "status", "IN_REVIEW", "priority", "HIGH"), moderator(ALICE));

        assertEquals(200, changed.status(), changed.body());
        assertEquals("IN_REVIEW", changed.json().get("status"));
        assertEquals(1, ((Number) changed.json().get("version")).intValue());
        List<Map<String, Object>> items = timeline(id);
        assertEquals(List.of("case.status_changed", "case.priority_changed"), items.stream().map(item -> item.get("type")).toList());
        @SuppressWarnings("unchecked") Map<String, Object> actor = (Map<String, Object>) items.getFirst().get("actor");
        assertEquals("VERIFIED", actor.get("trust"));
        assertEquals(ALICE.toString(), actor.get("oid"));
        assertEquals("Staff a11c", actor.get("name"));
    }

    @Test
    void aStaleVersionAnswers409() {
        String id = givenPunishment();
        patch("/case/" + id, Map.of("expectedVersion", 0, "status", "WAITING"), moderator(ALICE));

        Reply stale = patch("/case/" + id, Map.of("expectedVersion", 0, "status", "CLOSED"), moderator(BOB));

        assertEquals(409, stale.status(), stale.body());
        assertEquals("WAITING", get("/case/" + id).json().get("status"), "the stale write did not apply");
    }

    @Test
    void aViewerCannotChangeACase() {
        String id = givenPunishment();

        assertEquals(403, patch("/case/" + id, Map.of("expectedVersion", 0, "status", "CLOSED"), this.jwt.viewer()).status());
    }

    @Test
    void theProxyCannotReadACase() {
        assertEquals(403, get("/case/" + givenPunishment(), this.jwt.proxy()).status());
    }

    @Test
    void anAssigneeMustBeAKnownHumanStaffMember() {
        String id = givenPunishment();

        Reply unknown = patch("/case/" + id, Map.of("expectedVersion", 0, "assigneeOid", UUID.randomUUID().toString()), moderator(ALICE));

        assertEquals(422, unknown.status(), unknown.body());
    }

    @Test
    void aCaseCanBeAssignedToAStaffMemberWhoHasWritten() {
        String id = givenPunishment();
        addNote(id, moderator(BOB), "hello");

        Reply assigned = patch("/case/" + id, Map.of("expectedVersion", 0, "assigneeOid", BOB.toString()), moderator(ALICE));

        assertEquals(200, assigned.status(), assigned.body());
        assertEquals(BOB.toString(), assigned.json().get("assigneeOid"));
    }

    @Test
    void tooManyTagsAnswer400() {
        String id = givenPunishment();
        List<String> tags = java.util.stream.IntStream.range(0, 11).mapToObj(i -> "t" + i).toList();

        assertEquals(400, patch("/case/" + id, Map.of("expectedVersion", 0, "tags", tags), moderator(ALICE)).status());
    }

    @Test
    void aNoteWithAVisibilityOfPlayerIsStoredAsStaff() {
        String id = givenPunishment();

        post("/case/" + id + "/notes", Map.of("text", "internal", "visibility", "PLAYER"), moderator(ALICE));

        assertEquals("STAFF", timeline(id).getFirst().get("visibility"));
    }

    @Test
    void aViewerReadsNotesButCannotAddOne() {
        String id = givenPunishment();
        addNote(id, moderator(ALICE), "visible to staff");

        assertEquals(200, get("/case/" + id + "/timeline", this.jwt.viewer()).status());
        assertEquals(403, post("/case/" + id + "/notes", Map.of("text", "x"), this.jwt.viewer()).status());
    }

    @Test
    void aNoteWithAUuidAnswers400AndStoresNothing() {
        String id = givenPunishment();

        Reply reply = post("/case/" + id + "/notes", Map.of("text", "player " + UUID.randomUUID()), moderator(ALICE));

        assertEquals(400, reply.status());
        assertTrue(timeline(id).isEmpty(), "nothing was stored");
    }

    @Test
    void anotherModeratorCannotEditAnAuthorsNoteButAnAdminCan() {
        String id = givenPunishment();
        String note = addNote(id, moderator(ALICE), "first");

        assertEquals(403, put("/case/" + id + "/notes/" + note, Map.of("text", "mine"), moderator(BOB)).status());
        assertEquals(200, put("/case/" + id + "/notes/" + note, Map.of("text", "edited"), moderator(ALICE)).status());
        assertEquals(200, put("/case/" + id + "/notes/" + note, Map.of("text", "edited by admin"), admin(UUID.randomUUID())).status());
        assertEquals(List.of("note.added", "note.edited", "note.edited"), timeline(id).stream().map(item -> item.get("type")).toList());
    }

    @Test
    void anAdminRedactsTheWholeNoteAndAModeratorCannot() {
        String id = givenPunishment();
        String note = addNote(id, moderator(ALICE), "secret");
        put("/case/" + id + "/notes/" + note, Map.of("text", "secret 2"), moderator(ALICE));

        assertEquals(403, post("/case/" + id + "/notes/" + note + "/redact", Map.of("reason", "PERSONAL_DATA"), moderator(ALICE)).status());
        assertEquals(400, post("/case/" + id + "/notes/" + note + "/redact", Map.of(), admin(UUID.randomUUID())).status(), "a reason is required");
        Reply redacted = post("/case/" + id + "/notes/" + note + "/redact", Map.of("reason", "PERSONAL_DATA"), admin(UUID.randomUUID()));

        assertEquals(200, redacted.status(), redacted.body());
        assertEquals(2, ((Number) redacted.json().get("redactedVersions")).intValue());
        List<Map<String, Object>> items = timeline(id);
        assertEquals(List.of("note.added", "note.edited", "note.redacted"), items.stream().map(item -> item.get("type")).toList());
        assertTrue(items.subList(0, 2).stream().allMatch(item -> item.get("redactedAt") != null && ((Map<?, ?>) item.get("payload")).isEmpty()),
                "no note text is served");
        assertFalse(get("/case/" + id + "/timeline").body().contains("secret"), "the text is gone from the response");
    }

    @Test
    void anAnonymizedStaffMemberShowsNoNameButKeepsTheOid() {
        String id = givenPunishment();
        addNote(id, moderator(ALICE), "a note");

        post("/admin/staff-actor/" + ALICE + "/anonymize", null, this.jwt.admin());

        @SuppressWarnings("unchecked") Map<String, Object> actor = (Map<String, Object>) timeline(id).getFirst().get("actor");
        assertNull(actor.get("name"), "the name is erased");
        assertEquals(ALICE.toString(), actor.get("oid"));
    }

    @Test
    void theTimelineIsPagedByCursorAndFilteredByType() {
        String id = givenPunishment();
        for (int i = 0; i < 5; i++) {
            addNote(id, moderator(ALICE), "note " + i);
        }
        patch("/case/" + id, Map.of("expectedVersion", 0, "status", "WAITING"), moderator(ALICE));

        Reply first = get("/case/" + id + "/timeline?size=4", this.jwt.viewer());
        String cursor = (String) first.json().get("nextCursor");
        Reply second = get("/case/" + id + "/timeline?size=4&cursor=" + cursor, this.jwt.viewer());
        Reply notes = get("/case/" + id + "/timeline?type=note", this.jwt.viewer());
        Reply oversized = get("/case/" + id + "/timeline?size=1000", this.jwt.viewer());

        assertEquals(4, ((List<?>) first.json().get("items")).size());
        assertEquals(2, ((List<?>) second.json().get("items")).size(), "the rest follows the cursor");
        assertNull(second.json().get("nextCursor"), "the last page has no cursor");
        assertEquals(5, ((List<?>) notes.json().get("items")).size(), "the note group excludes the status change");
        assertEquals(6, ((List<?>) oversized.json().get("items")).size(), "an oversized page is clamped, not refused");
    }

    @Test
    void relatedListsTheOtherPunishmentsOfTheSamePlayerWithoutTheOwner() {
        String first = givenPunishment();
        String second = givenPunishment();

        Reply reply = get("/case/" + first, this.jwt.viewer());

        assertTrue(((List<?>) reply.json().get("related")).stream().anyMatch(item -> second.equals(((Map<?, ?>) item).get("identifier"))));
        assertFalse(reply.body().contains(OWNER), "the owner hash is never served in full");
    }

    @Test
    void theCaseListFiltersByStatus() {
        String id = givenPunishment();
        patch("/case/" + id, Map.of("expectedVersion", 0, "status", "IN_REVIEW", "tags", List.of("listed-" + id.substring(0, 8))), moderator(ALICE));

        Reply reply = get("/case?status=IN_REVIEW&tag=listed-" + id.substring(0, 8), this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(List.of(id), ((List<?>) reply.json().get("items")).stream().map(item -> ((Map<?, ?>) item).get("punishmentIdentifier")).toList());
    }

    @Test
    void anUnknownPunishmentAnswers404() {
        assertEquals(404, get("/case/" + UUID.randomUUID(), this.jwt.viewer()).status());
    }
}
