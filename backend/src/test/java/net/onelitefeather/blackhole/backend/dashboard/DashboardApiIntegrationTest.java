package net.onelitefeather.blackhole.backend.dashboard;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class DashboardApiIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("dashboard-staff".getBytes()).toString();

    @SuppressWarnings("unchecked")
    private static long activeIn(Map<String, Object> summary, String section) {
        return ((List<Map<String, Object>>) summary.get("activePunishmentsBySection")).stream()
                .filter(entry -> section.equals(entry.get("section")))
                .mapToLong(entry -> ((Number) entry.get("active")).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("section " + section + " missing in " + summary));
    }

    private static long number(Map<String, Object> summary, String field) {
        return ((Number) summary.get(field)).longValue();
    }

    @Test
    void theSummaryListsTheDefaultSectionsAndHasAllCounters() {
        Reply reply = get("/dashboard/summary");

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> summary = reply.json();
        assertTrue(activeIn(summary, "chat") >= 0 && activeIn(summary, "network") >= 0, "default sections present");
        for (String field : List.of("openReports", "pendingAppeals", "escalationsLast7Days")) {
            assertNotNull(summary.get(field), field + " must be present: " + reply.body());
        }
        assertNotNull(summary.get("latestEscalations"), "latestEscalations must be present");
    }

    @Test
    void anAutomaticPermanentBanShowsUpAsActiveAndAsEscalation() {
        Map<String, Object> before = get("/dashboard/summary").json();
        String owner = "d1".repeat(64);

        Reply adjust = post("/elo/" + owner + "/adjust", Map.of("section", "network", "delta", -800, "source", STAFF));
        assertEquals(200, adjust.status(), adjust.body());

        Map<String, Object> after = get("/dashboard/summary").json();
        assertEquals(activeIn(before, "network") + 1, activeIn(after, "network"), "one more active network punishment");
        assertEquals(number(before, "escalationsLast7Days") + 1, number(after, "escalationsLast7Days"), "one more escalation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> latest = (List<Map<String, Object>>) after.get("latestEscalations");
        Map<String, Object> newest = latest.getFirst();
        assertEquals(owner, newest.get("owner"), "the new escalation is the newest");
        assertEquals("network", newest.get("section"));
        assertEquals(false, newest.get("lifted"));
    }
}
