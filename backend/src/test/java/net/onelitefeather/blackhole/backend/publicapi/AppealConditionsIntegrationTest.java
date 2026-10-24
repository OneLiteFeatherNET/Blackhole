package net.onelitefeather.blackhole.backend.publicapi;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The appeal conditions end to end: the staff routes and their roles, the public route and the version check when filing. */
@Tag("integration")
class AppealConditionsIntegrationTest extends PublicApiTestBase {

    private static final String SAVED = "Be kind. Nothing else matters.";

    /** Saves a text as ADMIN and returns the new version. */
    private int save(String text) {
        Reply saved = post("/appeal-conditions", Map.of("text", text), this.jwt.admin());
        assertEquals(200, saved.status(), saved.body());
        return ((Number) saved.json().get("version")).intValue();
    }

    @Test
    void theDefaultTextIsSeededAsVersionOne() {
        List<Map<String, Object>> versions = get("/appeal-conditions/versions", this.jwt.viewer()).jsonList();

        Map<String, Object> first = versions.getLast();
        assertEquals(1, ((Number) first.get("version")).intValue());
        assertTrue(((String) first.get("text")).contains("one appeal per Ban ID"), "the default text is seeded");
    }

    private int currentVersion() {
        return ((Number) get("/appeal-conditions", this.jwt.viewer()).json().get("version")).intValue();
    }

    @Test
    void aSaveCreatesTheNextVersionAndKeepsTheEarlierOnes() {
        int first = save(SAVED);
        int second = save(SAVED + " Again.");

        assertEquals(second, first + 1);
        List<Map<String, Object>> versions = get("/appeal-conditions/versions", this.jwt.viewer()).jsonList();
        assertEquals(second, ((Number) versions.getFirst().get("version")).intValue(), "newest first");
        assertEquals(1, ((Number) versions.getLast().get("version")).intValue(), "version 1 is still there");
        assertEquals(SAVED, versions.stream().filter(v -> ((Number) v.get("version")).intValue() == first).findFirst().orElseThrow().get("text"));
    }

    @Test
    void everyTeamRoleMayReadButOnlyAnAdminMaySave() {
        assertEquals(200, get("/appeal-conditions", this.jwt.viewer()).status());
        assertEquals(200, get("/appeal-conditions/versions", this.jwt.moderator()).status());
        assertEquals(403, post("/appeal-conditions", Map.of("text", SAVED), this.jwt.moderator()).status(), "moderator cannot save");
        assertEquals(403, post("/appeal-conditions", Map.of("text", SAVED), this.jwt.viewer()).status(), "viewer cannot save");
    }

    @Test
    void theProxyTheBotAndAPlayerAreDeniedOnTheStaffRoutes() {
        assertEquals(403, get("/appeal-conditions", this.jwt.proxy()).status(), "proxy");
        assertEquals(403, get("/appeal-conditions", this.jwt.discord()).status(), "bot");
        String player = sessionFor(punish("c1".repeat(64), "cond-roles"));
        assertTrue(List.of(401, 403).contains(playerGet("/appeal-conditions", player).status()), "a player session is no staff credential");
    }

    @Test
    void anInvalidTextIsRefusedAndCreatesNoVersion() {
        int before = get("/appeal-conditions/versions", this.jwt.viewer()).jsonList().size();
        assertEquals(400, post("/appeal-conditions", Map.of("text", " "), this.jwt.admin()).status());
        assertEquals(400, post("/appeal-conditions", Map.of("text", "x".repeat(4001)), this.jwt.admin()).status());

        assertEquals(before, get("/appeal-conditions/versions", this.jwt.viewer()).jsonList().size());
    }

    @Test
    void aPlayerReadsTheCurrentTextWithItsVersion() {
        int version = save(SAVED);
        String token = sessionFor(punish("c2".repeat(64), "cond-read"));

        Raw read = playerGet("/public/appeal/conditions", token);

        assertEquals(200, read.status(), read.body());
        assertEquals(version, ((Number) read.json().get("version")).intValue());
        assertEquals(SAVED, read.json().get("text"));
        assertEquals("no-store", read.header("cache-control"));
        assertFalse(read.body().contains("recorded"), "no staff data");
    }

    @Test
    void thePublicRouteNeedsASession() {
        assertEquals(401, playerGet("/public/appeal/conditions", null).status());
    }

    @Test
    void filingWithTheCurrentVersionStoresThatVersionOnTheAppeal() {
        int version = save(SAVED);
        String punishment = punish("c3".repeat(64), "cond-file");
        String token = sessionFor(punishment);

        Raw filed = playerPost("/public/appeal", token, Map.of("conditionsVersion", version, "statement", "Please look at my case again."));

        assertEquals(200, filed.status(), filed.body());
        assertEquals(List.of((long) version), storedVersions(punishment), "the accepted version is in the appeal metadata");
    }

    @Test
    void filingWithAnOldVersionIsRefusedAndRecordsNothing() {
        String punishment = punish("c4".repeat(64), "cond-old");
        String token = sessionFor(punishment);
        int shown = currentVersion();
        save(SAVED);

        Raw filed = playerPost("/public/appeal", token, Map.of("conditionsVersion", shown, "statement", "Please look at my case again."));

        assertEquals(409, filed.status(), filed.body());
        assertEquals("CONDITIONS_CHANGED", filed.json().get("code"));
        assertEquals(0, storedVersions(punishment).size(), "no appeal exists");
    }

    @Test
    void filingWithoutAVersionIsRefused() {
        String punishment = punish("c5".repeat(64), "cond-missing");
        String token = sessionFor(punishment);

        Raw filed = playerPost("/public/appeal", token, Map.of("statement", "Please look at my case again."));

        assertEquals(409, filed.status(), filed.body());
        assertEquals("CONDITIONS_CHANGED", filed.json().get("code"));
        assertEquals(0, storedVersions(punishment).size());
    }

    @Test
    void filingWithANewerVersionIsRefused() {
        String punishment = punish("c6".repeat(64), "cond-new");
        String token = sessionFor(punishment);

        Raw filed = playerPost("/public/appeal", token, Map.of("conditionsVersion", currentVersion() + 50, "statement", "Please look at my case again."));

        assertEquals(409, filed.status(), filed.body());
        assertEquals("CONDITIONS_CHANGED", filed.json().get("code"));
    }

    @Test
    void aForfeitedRightStillAnswersForfeitedWithAStaleVersion() {
        String punishment = punish("c7".repeat(64), "cond-leak");
        String banId = banIdOf(punishment);
        int shown = currentVersion();
        markLeaked(punishment);
        save(SAVED);
        String token = (String) login(banId).json().get("token");

        Raw filed = playerPost("/public/appeal", token, Map.of("conditionsVersion", shown, "statement", "Please look at my case again."));

        assertEquals(409, filed.status(), filed.body());
        assertEquals("FORFEITED", filed.json().get("code"));
    }

    @Test
    void aBadStatementIsStillAValidationErrorBeforeTheVersionIsChecked() {
        String token = sessionFor(punish("c8".repeat(64), "cond-statement"));

        assertEquals(400, playerPost("/public/appeal", token, Map.of("statement", "")).status());
    }

    /** The conditions version of every appeal of the punishment, read through the staff list. */
    private List<Long> storedVersions(String punishment) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) get("/appeal?size=100").json().get("content");
        return all.stream().filter(a -> punishment.equals(a.get("punishmentIdentifier")))
                .map(a -> ((Number) ((Map<?, ?>) a.get("metaData")).get("conditionsVersion")).longValue()).toList();
    }
}
