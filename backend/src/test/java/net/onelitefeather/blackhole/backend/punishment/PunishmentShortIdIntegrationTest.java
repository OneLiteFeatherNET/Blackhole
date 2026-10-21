package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class PunishmentShortIdIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    private String applyPunishment(String owner, String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "r", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", key, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    @Test
    void anAppliedPunishmentIsFoundByItsShortIdWithAndWithoutHashAndInAnyCase() {
        String identifier = applyPunishment("c3".repeat(64), "short-id-int");
        String key = PunishmentShortId.keyOf(identifier).orElseThrow();

        for (String typed : List.of("%23" + key, key, "%23" + key.toLowerCase(), key.toLowerCase())) {
            Reply found = get("/punishment/short/" + typed);
            assertEquals(200, found.status(), found.body());
            List<Map<String, Object>> list = found.jsonList();
            assertTrue(list.stream().anyMatch(p -> identifier.equals(p.get("identifier"))), typed + ": " + found.body());
            assertTrue(list.stream().allMatch(p -> ("#" + key).equals(p.get("shortId"))), typed + ": " + found.body());
        }
    }

    @Test
    void aShortIdNobodyHasIsAnEmptyList() {
        Reply found = get("/punishment/short/00000000");
        assertEquals(200, found.status(), found.body());
        assertEquals(0, found.jsonList().size());
    }

    @Test
    void aMalformedShortIdIsABadRequestWithTheTypedError() {
        Reply bad = get("/punishment/short/not-hex");
        assertEquals(400, bad.status(), bad.body());
        assertEquals("VALIDATION", bad.json().get("kind"));
    }
}
