package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule hits against real PostgreSQL: the one-statement counter, roles, template application, retention and erasure. */
@Tag("integration")
class ClientRuleHitApiIntegrationTest extends ApiIntegrationTest {

    private static String owner() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return (hex + hex + hex + hex).substring(0, 64) + (hex + hex + hex + hex).substring(0, 64);
    }

    private static Map<String, Object> channelRule(String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("displayName", "Rule " + key);
        body.put("kind", "CHANNEL");
        body.put("patterns", List.of("zq-secret-channel:*"));
        return body;
    }

    private Reply hit(String rule, String owner, boolean bypassed) {
        return post("/client-rule/hit", Map.of("rule", rule, "owner", owner, "server", "lobby-1", "enforced", false, "bypassed", bypassed), this.jwt.proxy());
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void aHitIsStoredCountedOnTheRuleAndListed() {
        post("/client-rule", channelRule("hit-count"));
        String owner = owner();

        assertEquals(200, hit("hit-count", owner, false).status());
        assertEquals(200, hit("hit-count", owner, false).status());

        assertEquals(2, get("/client-rule/hit-count").json().get("hitCount"));
        List<Map<String, Object>> hits = castList(get("/client-rule/hit-count/hits", this.jwt.viewer()).json().get("hits"));
        assertEquals(2, hits.size());
        assertEquals(owner, hits.getFirst().get("owner"));
        assertEquals("lobby-1", hits.getFirst().get("server"));
        assertEquals("NOTIFY", hits.getFirst().get("action"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    void theStoredHitHoldsNoTraitAndNoRulePattern() throws SQLException {
        post("/client-rule", channelRule("hit-no-trait"));
        hit("hit-no-trait", owner(), false);

        assertEquals(0, count("SELECT count(*) FROM client_rule_hit WHERE client_rule_hit::text LIKE '%zq-secret-channel%'"));
        assertTrue(count("SELECT count(*) FROM client_rule_hit WHERE rule_key = 'hit-no-trait'") > 0);
    }

    @Test
    void aHitOnAnUnknownOrDisabledRuleIsNotFoundAndStoresNothing() throws SQLException {
        assertEquals(404, hit("hit-ghost", owner(), false).status());
        post("/client-rule", channelRule("hit-off"));
        Map<String, Object> off = new HashMap<>(channelRule("hit-off"));
        off.put("enabled", false);
        put("/client-rule/hit-off", off, this.jwt.admin());

        assertEquals(404, hit("hit-off", owner(), false).status());

        assertEquals(0, count("SELECT count(*) FROM client_rule_hit WHERE rule_key = 'hit-off'"));
        assertEquals(0, get("/client-rule/hit-off").json().get("hitCount"));
    }

    @Test
    void aMalformedOwnerIsRejected() {
        post("/client-rule", channelRule("hit-bad-owner"));

        assertEquals(400, post("/client-rule/hit", Map.of("rule", "hit-bad-owner", "owner", "nope", "enforced", false, "bypassed", false), this.jwt.proxy()).status());
    }

    @Test
    void onlyTheProxyMayReportAndOnlyTheTeamMayRead() {
        post("/client-rule", channelRule("hit-roles"));
        String owner = owner();

        assertEquals(403, post("/client-rule/hit", Map.of("rule", "hit-roles", "owner", owner, "enforced", false, "bypassed", false), this.jwt.admin()).status());
        assertEquals(403, post("/client-rule/hit", Map.of("rule", "hit-roles", "owner", owner, "enforced", false, "bypassed", false), this.jwt.viewer()).status());
        hit("hit-roles", owner, false);
        for (String token : List.of(this.jwt.viewer(), this.jwt.moderator(), this.jwt.admin())) {
            assertEquals(200, get("/client-rule/hit-roles/hits", token).status());
            assertEquals(200, get("/client-rule/hits?owner=" + owner, token).status());
        }
        assertEquals(403, get("/client-rule/hit-roles/hits", this.jwt.proxy()).status(), "the proxy may not read hits");
        assertEquals(403, get("/client-rule/hits?owner=" + owner, this.jwt.proxy()).status());
        assertEquals(401, get("/client-rule/hit-roles/hits", null).status());
    }

    @Test
    void theHitsOfAPlayerAreListedAcrossRules() {
        post("/client-rule", channelRule("hit-owner-a"));
        post("/client-rule", channelRule("hit-owner-b"));
        String owner = owner();
        hit("hit-owner-a", owner, false);
        hit("hit-owner-b", owner, true);

        List<Map<String, Object>> hits = castList(get("/client-rule/hits?owner=" + owner).json().get("hits"));

        assertEquals(2, hits.size());
        assertEquals(1, hits.stream().filter(h -> Boolean.TRUE.equals(h.get("bypassed"))).count());
        assertEquals(400, get("/client-rule/hits?owner=nope").status());
    }

    @Test
    void aTemplateRuleAppliesItsTemplateOnceAndNeverToABypassedPlayer() {
        Map<String, Object> template = new HashMap<>();
        template.put("key", "hit-cheat");
        template.put("reason", "Old client");
        template.put("section", "network");
        template.put("steps", List.of(Map.of("duration", "P1D", "eloCost", 50), Map.of("duration", "P7D", "eloCost", 50)));
        assertEquals(200, post("/template", template).status());
        Map<String, Object> rule = new HashMap<>();
        rule.put("key", "hit-template");
        rule.put("displayName", "Old clients");
        rule.put("kind", "PROTOCOL");
        rule.put("protocolMin", 1);
        rule.put("protocolMax", 100);
        rule.put("action", "TEMPLATE");
        rule.put("templateKey", "hit-cheat");
        assertEquals(200, post("/client-rule", rule).status());
        String owner = owner();

        Reply bypassed = hit("hit-template", owner, true);
        assertEquals(false, bypassed.json().get("templateApplied"), "staff are exempt");
        Reply first = hit("hit-template", owner, false);
        assertEquals(true, first.json().get("templateApplied"), first.body());
        assertNotNull(first.json().get("punishmentIdentifier"));
        Reply second = hit("hit-template", owner, false);

        assertEquals(false, second.json().get("templateApplied"), "already punished by this rule");
        Map<String, Object> profile = get("/profile/" + owner).json();
        assertTrue(profile.toString().contains("hit-template"), "the punishment names its rule: " + profile);
        assertEquals(3, ((List<?>) get("/client-rule/hit-template/hits").json().get("hits")).size(), "all three hits are recorded");
    }

    @Test
    void expiredHitsAreSweptButTheRuleKeepsItsTotal() throws SQLException {
        post("/client-rule", channelRule("hit-sweep"));
        hit("hit-sweep", owner(), false);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE client_rule_hit SET at = 1 WHERE rule_key = 'hit-sweep'");
        }

        this.context.getBean(ClientRuleHitRetentionSweeper.class).sweep();

        assertEquals(0, count("SELECT count(*) FROM client_rule_hit WHERE rule_key = 'hit-sweep'"));
        assertEquals(1, get("/client-rule/hit-sweep").json().get("hitCount"));
    }

    @Test
    void erasingAPlayerDeletesTheirHits() throws SQLException {
        post("/client-rule", channelRule("hit-erase"));
        String owner = owner();
        hit("hit-erase", owner, false);
        hit("hit-erase", owner(), false);

        delete("/profile/delete/" + owner, this.jwt.admin());

        assertEquals(0, count("SELECT count(*) FROM client_rule_hit WHERE owner_hash = '" + owner + "'"));
        assertEquals(1, count("SELECT count(*) FROM client_rule_hit WHERE rule_key = 'hit-erase'"), "other players keep theirs");
    }

    @Test
    void deletingARuleDeletesItsHits() throws SQLException {
        post("/client-rule", channelRule("hit-cascade"));
        hit("hit-cascade", owner(), false);

        delete("/client-rule/hit-cascade", this.jwt.admin());

        assertEquals(0, count("SELECT count(*) FROM client_rule_hit WHERE rule_key = 'hit-cascade'"));
        assertFalse(get("/client-rule").body().contains("hit-cascade"));
    }
}
