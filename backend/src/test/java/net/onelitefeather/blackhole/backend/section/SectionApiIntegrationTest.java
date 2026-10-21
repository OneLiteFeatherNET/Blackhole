package net.onelitefeather.blackhole.backend.section;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class SectionApiIntegrationTest extends ApiIntegrationTest {

    private static Map<String, Object> section(String key, String enforcement, List<String> servers) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("key", key);
        body.put("displayName", key);
        body.put("enforcement", enforcement);
        body.put("servers", servers);
        body.put("baseScore", 1000);
        body.put("softThreshold", 700);
        body.put("softDuration", "P3D");
        body.put("permanentThreshold", 300);
        body.put("pardonScore", 700);
        body.put("permanentReason", "banned for good");
        return body;
    }

    @Test
    void theDefaultSectionsAreListed() {
        Reply reply = get("/section");

        assertEquals(200, reply.status(), reply.body());
        List<Object> keys = reply.jsonList().stream().map(s -> s.get("key")).toList();
        assertTrue(keys.contains("chat") && keys.contains("network"), "default sections listed, got " + keys);
    }

    @Test
    void aServerSectionIsCreatedWithItsPatternsAndReadBack() {
        Reply created = post("/section", section("bedwars-crud", "SERVER", List.of("bedwars-*", "bw-*")));

        assertEquals(200, created.status(), created.body());
        Reply read = get("/section/bedwars-crud");
        assertEquals(200, read.status(), read.body());
        assertEquals(List.of("bedwars-*", "bw-*"), read.json().get("servers"), "patterns survive the database round trip");
        assertEquals("SERVER", read.json().get("enforcement"));
        assertEquals(true, read.json().get("enabled"));
    }

    @Test
    void creatingADuplicateKeyIsRejectedWithConflict() {
        post("/section", section("dup-key", "BAN", List.of()));

        Reply second = post("/section", section("dup-key", "BAN", List.of()));

        assertEquals(409, second.status(), second.body());
    }

    @Test
    void invalidThresholdsAreRejectedWithBadRequest() {
        Map<String, Object> invalid = section("bad-thresholds", "BAN", List.of());
        invalid.put("permanentThreshold", 1200);

        Reply reply = post("/section", invalid);

        assertEquals(400, reply.status(), reply.body());
        assertEquals(404, get("/section/bad-thresholds").status(), "nothing stored");
    }

    @Test
    void anUpdateChangesTheStoredSection() {
        post("/section", section("to-update", "BAN", List.of()));
        Map<String, Object> update = section("to-update", "BAN", List.of());
        update.put("displayName", "Renamed");
        update.put("decayPerDay", 25);
        update.put("enabled", true);

        Reply reply = post("/section/update", update);

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> read = get("/section/to-update").json();
        assertEquals("Renamed", read.get("displayName"));
        assertEquals(25, read.get("decayPerDay"));
    }

    @Test
    void updatingAnUnknownSectionIsNotFound() {
        Map<String, Object> update = section("never-created", "BAN", List.of());
        update.put("decayPerDay", 5);
        update.put("enabled", true);

        assertEquals(404, post("/section/update", update).status());
    }

    @Test
    void aDisabledSectionIsStillListedButMarkedDisabled() {
        post("/section", section("to-disable", "MUTE", List.of()));

        Reply disabled = post("/section/to-disable/disable", null);

        assertEquals(200, disabled.status(), disabled.body());
        assertEquals(false, disabled.json().get("enabled"));
        Map<String, Object> listed = get("/section").jsonList().stream()
                .filter(s -> "to-disable".equals(s.get("key"))).findFirst().orElseThrow();
        assertEquals(false, listed.get("enabled"), "the list reflects the disable, so no stale cache entry");
    }
}
