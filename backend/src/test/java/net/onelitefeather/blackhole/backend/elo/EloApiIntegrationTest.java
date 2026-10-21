package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class EloApiIntegrationTest extends ApiIntegrationTest {

    private static Map<String, Object> adjustment(String section, int delta) {
        return Map.of("section", section, "delta", delta, "note", "integration test", "source", UUID.randomUUID().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sectionOf(Reply profile, String key) {
        return ((List<Map<String, Object>>) profile.json().get("sections")).stream()
                .filter(s -> key.equals(s.get("section"))).findFirst().orElseThrow();
    }

    @Test
    void anOwnerWithoutAnyEventGetsBaseScoresInsteadOfNotFound() {
        Reply profile = get("/elo/" + "11".repeat(64));

        assertEquals(200, profile.status(), profile.body());
        assertEquals(1000, sectionOf(profile, "chat").get("score"));
        assertEquals(1000, sectionOf(profile, "network").get("score"));
        assertEquals(300, sectionOf(profile, "network").get("permanentThreshold"), "thresholds are part of the profile");
    }

    @Test
    void anAdjustmentChangesOnlyTheNamedSectionAndIsVisibleInTheProfile() {
        String owner = "22".repeat(64);

        Reply adjust = post("/elo/" + owner + "/adjust", adjustment("chat", -50));

        assertEquals(200, adjust.status(), adjust.body());
        assertEquals(1000, adjust.json().get("scoreBefore"));
        assertEquals(950, adjust.json().get("scoreAfter"));
        Reply profile = get("/elo/" + owner);
        assertEquals(950, sectionOf(profile, "chat").get("score"), "the cached standing was evicted by the write");
        assertEquals(1000, sectionOf(profile, "network").get("score"));
    }

    @Test
    void theHistoryCanBeFilteredBySection() {
        String owner = "33".repeat(64);
        post("/elo/" + owner + "/adjust", adjustment("chat", -10));
        post("/elo/" + owner + "/adjust", adjustment("network", -20));

        Reply all = get("/elo/" + owner + "/history");
        Reply chatOnly = get("/elo/" + owner + "/history?section=chat");

        assertEquals(2, ((List<?>) all.json().get("content")).size(), all.body());
        List<?> chatEvents = (List<?>) chatOnly.json().get("content");
        assertEquals(1, chatEvents.size(), chatOnly.body());
        assertEquals("chat", ((Map<?, ?>) chatEvents.getFirst()).get("section"));
        assertEquals("MANUAL_ADJUSTMENT", ((Map<?, ?>) chatEvents.getFirst()).get("reasonCode"));
    }

    @Test
    void anAdjustmentInAnUnknownSectionIsNotFound() {
        Reply reply = post("/elo/" + "44".repeat(64) + "/adjust", adjustment("ghost", -10));

        assertEquals(404, reply.status(), reply.body());
    }

    @Test
    void anAdjustmentOfZeroIsABadRequest() {
        Reply reply = post("/elo/" + "55".repeat(64) + "/adjust", adjustment("chat", 0));

        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void anOwnerThatIsNotAHashIsABadRequest() {
        assertEquals(400, get("/elo/not-a-hash").status());
        assertTrue(post("/elo/not-a-hash/adjust", adjustment("chat", -10)).status() == 400);
    }
}
