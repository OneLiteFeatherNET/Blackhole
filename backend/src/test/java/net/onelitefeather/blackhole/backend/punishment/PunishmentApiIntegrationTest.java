package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class PunishmentApiIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    private static Map<String, Object> template(String key, String section, Object... durationsAndCosts) {
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        for (int i = 0; i < durationsAndCosts.length; i += 2) {
            steps.add(Map.of("duration", durationsAndCosts[i], "eloCost", durationsAndCosts[i + 1]));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("reason", "Reason of " + key);
        body.put("section", section);
        body.put("steps", steps);
        return body;
    }

    private Reply apply(String owner, String templateKey) {
        return post("/punishment/apply", Map.of("owner", owner, "templateKey", templateKey, "source", STAFF));
    }

    private Reply adjust(String owner, String section, int delta) {
        return post("/elo/" + owner + "/adjust", Map.of("section", section, "delta", delta, "source", STAFF));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void staffTempBansClimbTheStepsDeductEloAndEscalateToAPermanentBan() {
        String owner = "a1".repeat(64);
        assertEquals(200, post("/template", template("int-hacking", "network", "P1D", 100, "P7D", 150, "P30D", 250)).status());

        Reply first = apply(owner, "int-hacking");
        assertEquals(200, first.status(), first.body());
        assertEquals(1, first.json().get("step"));
        assertEquals(3, first.json().get("stepCount"));
        assertEquals(1000, first.json().get("scoreBefore"));
        assertEquals(900, first.json().get("scoreAfter"));
        assertNull(first.json().get("escalation"));

        Reply second = apply(owner, "int-hacking");
        assertEquals(2, second.json().get("step"), "the repeat offence uses the second step: " + second.body());
        assertEquals(750, second.json().get("scoreAfter"));

        Reply third = apply(owner, "int-hacking");
        assertEquals(3, third.json().get("step"));
        assertEquals(500, third.json().get("scoreAfter"));

        Reply fourth = apply(owner, "int-hacking");
        assertEquals(3, fourth.json().get("step"), "beyond the last step the last step repeats");
        assertEquals(250, fourth.json().get("scoreAfter"));
        Map<String, Object> escalation = map(fourth.json().get("escalation"));
        assertNotNull(escalation, "250 is below the permanent threshold of 300: " + fourth.body());
        assertEquals(true, map(escalation.get("punishment")).get("permanent"));

        Map<String, Object> profile = get("/profile/" + owner).json();
        Map<String, Object> activeNetwork = map(map(profile.get("active")).get("network"));
        assertEquals(true, activeNetwork.get("permanent"), "the permanent ban is the active network punishment");
        assertNull(activeNetwork.get("template"), "permanent punishments have no template");
        assertEquals(4, ((List<?>) profile.get("history")).size(), "all four staff temp bans moved to history");

        Reply revoke = post("/punishment/revoke", Map.of("owner", owner, "section", "network", "source", STAFF));
        assertEquals(200, revoke.status(), revoke.body());
        assertEquals(700, revoke.json().get("scoreReset"), "pardon score of the network section");
        assertEquals(404, post("/punishment/revoke", Map.of("owner", owner, "section", "network", "source", STAFF)).status(),
                "nothing left to revoke");

        Map<String, Object> elo = get("/elo/" + owner).json();
        Map<String, Object> network = ((List<Map<String, Object>>) elo.get("sections")).stream()
                .filter(s -> "network".equals(s.get("section"))).findFirst().orElseThrow();
        assertEquals(700, network.get("score"));
    }

    @Test
    void aMuteDoesNotLiftAnActiveBan() {
        String owner = "a2".repeat(64);
        post("/template", template("int-ban", "network", "P3D", 0));
        post("/template", template("int-mute", "chat", "PT1H", 0));

        apply(owner, "int-ban");
        apply(owner, "int-mute");

        Map<String, Object> active = map(get("/profile/" + owner).json().get("active"));
        assertTrue(active.containsKey("network") && active.containsKey("chat"), "both sections are active: " + active.keySet());
    }

    @Test
    void aTemplateWithAnIdentifierOnCreateIsRejected() {
        Map<String, Object> body = template("int-with-id", "network", "P1D", 10);
        body.put("identifier", UUID.randomUUID().toString());

        assertEquals(400, post("/template", body).status());
    }

    @Test
    void aDuplicateTemplateKeyIsAConflict() {
        post("/template", template("int-dup", "network", "P1D", 10));

        assertEquals(409, post("/template", template("int-dup", "network", "P1D", 10)).status());
    }

    @Test
    void aPermanentTemplateWithoutStepsIsRejected() {
        assertEquals(400, post("/template", template("int-no-steps", "network")).status());
    }

    @Test
    void aTemplateInAnUnknownSectionIsRejected() {
        assertEquals(400, post("/template", template("int-ghost", "ghost", "P1D", 10)).status());
    }

    @Test
    void applyingAnUnknownTemplateIsNotFound() {
        assertEquals(404, apply("a3".repeat(64), "int-does-not-exist").status());
    }

    @Test
    void applyingATemplateOfADisabledSectionIsAConflict() {
        post("/section", Map.of(
                "key", "int-disabled", "displayName", "Disabled", "enforcement", "BAN", "baseScore", 1000,
                "permanentThreshold", 300, "pardonScore", 700, "permanentReason", "banned"));
        post("/template", template("int-disabled-template", "int-disabled", "P1D", 10));
        post("/section/int-disabled/disable", null);

        assertEquals(409, apply("a4".repeat(64), "int-disabled-template").status());
    }

    @Test
    void aServerSectionBanLeavesTheNetworkScoreAndTheNetworkSlotAlone() {
        String owner = "a5".repeat(64);
        post("/section", Map.of(
                "key", "int-bedwars", "displayName", "Bedwars", "enforcement", "SERVER", "servers", List.of("int-bedwars-*"),
                "baseScore", 1000, "permanentThreshold", 300, "pardonScore", 700, "permanentReason", "banned from bedwars"));
        post("/template", template("int-bedwars-cheat", "int-bedwars", "P3D", 200));

        Reply reply = apply(owner, "int-bedwars-cheat");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("SERVER", reply.json().get("enforcement"));
        Map<String, Object> active = map(get("/profile/" + owner).json().get("active"));
        assertEquals(List.of("int-bedwars"), List.copyOf(active.keySet()), "only the server section is punished");
    }

    @Test
    void anAdjustmentBelowThePermanentThresholdBansPermanently() {
        String owner = "a6".repeat(64);

        Reply reply = adjust(owner, "network", -800);

        assertEquals(200, reply.status(), reply.body());
        assertEquals("PERMANENT", map(reply.json().get("escalation")).get("tier"));
        assertEquals(true, map(map(get("/profile/" + owner).json().get("active")).get("network")).get("permanent"));
    }
}
