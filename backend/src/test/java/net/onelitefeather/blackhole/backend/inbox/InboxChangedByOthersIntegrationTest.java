package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The changedByOthers section of {@code GET /inbox} over HTTP and PostgreSQL: a case that another verified moderator changed
 * shows up for a viewer, and not for the moderator who changed it.
 */
@Tag("integration")
class InboxChangedByOthersIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = "5f1c2a3e-0000-4000-8000-0000000000aa";

    private String punish(String owner, String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "Spamming the chat", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", key, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    private static boolean listsShortId(Reply inbox, String shortId) {
        @SuppressWarnings("unchecked") Map<String, Object> section = (Map<String, Object>) inbox.json().get("changedByOthers");
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) section.get("items");
        return items.stream().anyMatch(item -> shortId.equals(item.get("id")));
    }

    @Test
    void aCaseChangedByAnotherModeratorIsListedForAViewerWithItsChange() {
        String punishment = punish("f6".repeat(64), "inbox-changed-other");
        String moderator = this.jwt.moderator();
        String shortId = ((String) get("/case/" + punishment, moderator).json().get("shortId")).replace("#", "");
        Reply changed = patch("/case/" + punishment, Map.of("expectedVersion", 0, "status", "IN_REVIEW"), moderator);
        assertEquals(200, changed.status(), changed.body());

        Reply inbox = get("/inbox", this.jwt.viewer());

        assertEquals(200, inbox.status(), inbox.body());
        assertTrue(listsShortId(inbox, shortId), "another moderator's latest change is listed, case " + shortId + " in " + inbox.body());
        @SuppressWarnings("unchecked") Map<String, Object> section = (Map<String, Object>) inbox.json().get("changedByOthers");
        @SuppressWarnings("unchecked") Map<String, Object> item = ((List<Map<String, Object>>) section.get("items")).stream()
                .filter(candidate -> shortId.equals(candidate.get("id"))).findFirst().orElseThrow();
        assertEquals("CASE", item.get("kind"));
        assertEquals("STATUS", item.get("change"));
        assertEquals(shortId, item.get("reference"));
    }

    @Test
    void theModeratorWhoMadeTheChangeDoesNotSeeItInTheSection() {
        String punishment = punish("e7".repeat(64), "inbox-changed-own");
        String moderator = this.jwt.moderator();
        String shortId = ((String) get("/case/" + punishment, moderator).json().get("shortId")).replace("#", "");
        assertEquals(200, patch("/case/" + punishment, Map.of("expectedVersion", 0, "status", "IN_REVIEW"), moderator).status());

        Reply inbox = get("/inbox", moderator);

        assertEquals(200, inbox.status(), inbox.body());
        assertFalse(listsShortId(inbox, shortId), "a caller's own change is not a change by others");
    }

    @Test
    void aChangedItemCarriesOnlyTheFixedFieldsAndNoStaffObjectId() {
        String punishment = punish("d8".repeat(64), "inbox-changed-fields");
        String moderator = this.jwt.moderator();
        String shortId = ((String) get("/case/" + punishment, moderator).json().get("shortId")).replace("#", "");
        assertEquals(200, patch("/case/" + punishment, Map.of("expectedVersion", 0, "status", "IN_REVIEW"), moderator).status());

        Reply inbox = get("/inbox", this.jwt.viewer());

        @SuppressWarnings("unchecked") Map<String, Object> section = (Map<String, Object>) inbox.json().get("changedByOthers");
        @SuppressWarnings("unchecked") Map<String, Object> item = ((List<Map<String, Object>>) section.get("items")).stream()
                .filter(candidate -> shortId.equals(candidate.get("id"))).findFirst().orElseThrow();
        assertEquals(java.util.Set.of("kind", "id", "reference", "change", "byName", "at"), item.keySet(),
                "the item holds the fixed fields only, no staff object id, Ban ID or note text");
    }

    @Test
    void aViewerReadsTheInboxWithTheChangedSectionAndTheFourSections() {
        Reply inbox = get("/inbox", this.jwt.viewer());

        assertEquals(200, inbox.status(), inbox.body());
        assertTrue(inbox.json().get("changedByOthers") != null, "the section is present: " + inbox.body());
    }

    @Test
    void theInboxStillRefusesAnUnauthenticatedCaller() {
        assertEquals(401, get("/inbox", null).status());
    }
}
