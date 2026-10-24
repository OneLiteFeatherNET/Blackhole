package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client rule routes against real PostgreSQL: one CTE statement per write, the roles of every
 * route, and the template reference. Rule keys are unique per test because the database lives for
 * the whole class.
 */
@Tag("integration")
class ClientRuleApiIntegrationTest extends ApiIntegrationTest {

    private static Map<String, Object> channelRule(String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("displayName", "Rule " + key);
        body.put("kind", "CHANNEL");
        body.put("patterns", List.of("Meteor-Client:*", "other:*"));
        return body;
    }

    private static Map<String, Object> update(Map<String, Object> create) {
        Map<String, Object> body = new HashMap<>(create);
        body.put("enabled", true);
        return body;
    }

    @SuppressWarnings("unchecked")
    private String createTemplate(String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("reason", "Reason of " + key);
        body.put("section", "network");
        body.put("steps", List.of(Map.of("duration", "P1D", "eloCost", 100)));
        Reply reply = post("/template", body);
        assertEquals(200, reply.status(), reply.body());
        return (String) reply.json().get("identifier");
    }

    @Test
    void aRuleIsCreatedWithItsPatternsAndReadBackInOrder() {
        Reply created = post("/client-rule", channelRule("api-meteor"));

        assertEquals(200, created.status(), created.body());
        Reply read = get("/client-rule/api-meteor");
        assertEquals(200, read.status(), read.body());
        assertEquals(List.of("meteor-client:*", "other:*"), read.json().get("patterns"), "lower case, order kept through the child table");
        assertEquals("NOTIFY", read.json().get("action"), "the default action");
        assertEquals("GLOBAL", read.json().get("scope"));
        assertEquals(true, read.json().get("enabled"));
        assertEquals(0, read.json().get("hitCount"));
    }

    @Test
    void theCreatingAdminsObjectIdIsNeverReturned() {
        post("/client-rule", channelRule("api-oid"));

        assertTrue(!get("/client-rule/api-oid").body().contains("recordedBy"), "the actor is stored, not served");
    }

    @Test
    void aServerScopedRuleKeepsItsSectionsAndServers() {
        Map<String, Object> section = new HashMap<>();
        section.put("key", "api-minigames");
        section.put("displayName", "Minigames");
        section.put("enforcement", "SERVER");
        section.put("servers", List.of("bw-*"));
        section.put("baseScore", 1000);
        section.put("permanentThreshold", 300);
        section.put("pardonScore", 700);
        section.put("permanentReason", "banned");
        assertEquals(200, post("/section", section).status());
        Map<String, Object> rule = channelRule("api-scoped");
        rule.put("scope", "SERVERS");
        rule.put("sectionKeys", List.of("api-minigames"));
        rule.put("targetKind", "CLOUDNET_TASK");
        rule.put("servers", List.of("BedWars"));

        assertEquals(200, post("/client-rule", rule).status());

        Map<String, Object> read = get("/client-rule/api-scoped").json();
        assertEquals(List.of("api-minigames"), read.get("sectionKeys"));
        assertEquals(List.of("BedWars"), read.get("servers"));
        assertEquals("CLOUDNET_TASK", read.get("targetKind"));
    }

    @Test
    void aSectionThatDoesNotExistIsRejectedAndNothingIsStored() {
        Map<String, Object> rule = channelRule("api-unknown-section");
        rule.put("scope", "SERVERS");
        rule.put("sectionKeys", List.of("no-such-section"));

        assertEquals(400, post("/client-rule", rule).status());
        assertEquals(404, get("/client-rule/api-unknown-section").status());
    }

    @Test
    void aDuplicateKeyIsAConflict() {
        post("/client-rule", channelRule("api-dup"));

        assertEquals(409, post("/client-rule", channelRule("api-dup")).status());
    }

    @Test
    void anInvalidRuleIsRejectedWithBadRequest() {
        Map<String, Object> rule = channelRule("api-bad");
        rule.put("patterns", List.of("not valid!"));

        Reply reply = post("/client-rule", rule);

        assertEquals(400, reply.status(), reply.body());
        assertEquals(404, get("/client-rule/api-bad").status());
    }

    @Test
    void theCatalogueRouteIsNotShadowedByTheKeyRoute() {
        assertEquals(200, get("/client-rule/catalogue").status());
        assertEquals(400, post("/client-rule", channelRule("catalogue")).status(), "the word is reserved as a key");
    }

    @Test
    void anUpdateReplacesTheRuleAndItsChildren() {
        post("/client-rule", channelRule("api-update"));
        Map<String, Object> changed = update(channelRule("api-update"));
        changed.put("patterns", List.of("only:one"));
        changed.put("action", "DENY");
        changed.put("denyMessage", "Not allowed here.");
        changed.put("enabled", false);

        Reply reply = put("/client-rule/api-update", changed, this.jwt.admin());

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> read = get("/client-rule/api-update").json();
        assertEquals(List.of("only:one"), read.get("patterns"), "the old patterns are gone");
        assertEquals("DENY", read.get("action"));
        assertEquals(false, read.get("enabled"));
    }

    @Test
    void updatingAnUnknownRuleIsNotFound() {
        assertEquals(404, put("/client-rule/api-ghost", update(channelRule("api-ghost")), this.jwt.admin()).status());
    }

    @Test
    void aDeletedRuleIsGoneWithItsChildren() {
        post("/client-rule", channelRule("api-delete"));

        assertEquals(200, delete("/client-rule/api-delete", this.jwt.admin()).status());

        assertEquals(404, get("/client-rule/api-delete").status());
        assertEquals(404, delete("/client-rule/api-delete", this.jwt.admin()).status());
        assertEquals(200, post("/client-rule", channelRule("api-delete")).status(), "the key can be reused");
    }

    @Test
    void theListContainsEveryRuleSortedByKey() {
        post("/client-rule", channelRule("api-list-b"));
        post("/client-rule", channelRule("api-list-a"));

        List<Object> keys = get("/client-rule").jsonList().stream().map(rule -> rule.get("key")).toList();

        assertTrue(keys.indexOf("api-list-a") >= 0 && keys.indexOf("api-list-a") < keys.indexOf("api-list-b"), "sorted: " + keys);
    }

    @Test
    void aRuleOnABrandThatPunishesNeedsTheAcknowledgement() {
        createTemplate("api-cheating");
        Map<String, Object> rule = new HashMap<>();
        rule.put("key", "api-brand");
        rule.put("displayName", "Brand");
        rule.put("kind", "BRAND");
        rule.put("patterns", List.of("cheat*"));
        rule.put("action", "TEMPLATE");
        rule.put("templateKey", "api-cheating");

        assertEquals(400, post("/client-rule", rule).status(), "no acknowledgement");
        rule.put("acknowledgedSpoofable", true);
        assertEquals(200, post("/client-rule", rule).status());
        assertEquals(true, get("/client-rule/api-brand").json().get("acknowledgedSpoofable"));
    }

    @Test
    void aTemplateThatDoesNotExistIsRejected() {
        Map<String, Object> rule = new HashMap<>();
        rule.put("key", "api-no-template");
        rule.put("displayName", "Old clients");
        rule.put("kind", "PROTOCOL");
        rule.put("protocolMin", 1);
        rule.put("protocolMax", 100);
        rule.put("action", "TEMPLATE");
        rule.put("templateKey", "no-such-template");

        assertEquals(400, post("/client-rule", rule).status());
    }

    @Test
    void aTemplateThatARuleUsesCannotBeDeleted() {
        String identifier = createTemplate("api-in-use");
        Map<String, Object> rule = new HashMap<>();
        rule.put("key", "api-uses-template");
        rule.put("displayName", "Old clients");
        rule.put("kind", "PROTOCOL");
        rule.put("protocolMin", 1);
        rule.put("protocolMax", 100);
        rule.put("action", "TEMPLATE");
        rule.put("templateKey", "api-in-use");
        assertEquals(200, post("/client-rule", rule).status());

        assertEquals(409, delete("/template/delete/" + identifier, this.jwt.admin()).status(), "referenced by a rule");

        assertEquals(200, delete("/client-rule/api-uses-template", this.jwt.admin()).status());
        assertEquals(200, delete("/template/delete/" + identifier, this.jwt.admin()).status(), "free once the rule is gone");
    }

    @Test
    void aTemplateThatARuleUsesCannotBeRenamed() {
        String identifier = createTemplate("api-rename");
        Map<String, Object> rule = new HashMap<>();
        rule.put("key", "api-renamed-template");
        rule.put("displayName", "Old clients");
        rule.put("kind", "PROTOCOL");
        rule.put("protocolMin", 1);
        rule.put("protocolMax", 100);
        rule.put("action", "TEMPLATE");
        rule.put("templateKey", "api-rename");
        post("/client-rule", rule);
        Map<String, Object> rename = new HashMap<>();
        rename.put("identifier", identifier);
        rename.put("key", "api-rename-2");
        rename.put("reason", "r");
        rename.put("section", "network");
        rename.put("steps", List.of(Map.of("duration", "P1D", "eloCost", 100)));

        assertEquals(409, post("/template/update", rename).status());
    }

    // --- roles ---

    @Test
    void aModeratorAndAViewerCannotWriteRules() {
        assertEquals(403, post("/client-rule", channelRule("api-denied"), this.jwt.moderator()).status());
        assertEquals(403, post("/client-rule", channelRule("api-denied"), this.jwt.viewer()).status());
        assertEquals(403, put("/client-rule/api-x", update(channelRule("api-x")), this.jwt.moderator()).status());
        assertEquals(403, delete("/client-rule/api-x", this.jwt.moderator()).status());
    }

    @Test
    void theProxyCannotWriteRules() {
        assertEquals(403, post("/client-rule", channelRule("api-proxy"), this.jwt.proxy()).status());
        assertEquals(403, delete("/client-rule/api-x", this.jwt.proxy()).status());
    }

    @Test
    void everyTeamRoleAndTheProxyMayReadRules() {
        post("/client-rule", channelRule("api-read"));

        for (String token : List.of(this.jwt.viewer(), this.jwt.moderator(), this.jwt.admin(), this.jwt.proxy())) {
            assertEquals(200, get("/client-rule", token).status());
            assertEquals(200, get("/client-rule/api-read", token).status());
        }
    }

    @Test
    void aRequestWithoutATokenIsRefused() {
        assertEquals(401, get("/client-rule", null).status());
    }

    @Test
    void onlyTheProxyMayReportTheCatalogueAndOnlyTeamRolesMayReadIt() {
        Map<String, Object> snapshot = Map.of("proxyId", "Proxy-1",
                "brands", List.of(Map.of("name", "fabric", "players", 5)),
                "channels", List.of(), "commands", List.of("hub"), "protocols", List.of());

        assertEquals(200, put("/client-rule/catalogue", snapshot, this.jwt.proxy()).status());
        assertEquals(403, put("/client-rule/catalogue", snapshot, this.jwt.admin()).status(), "humans may not forge the catalogue");
        assertEquals(403, put("/client-rule/catalogue", snapshot, this.jwt.viewer()).status());
        assertEquals(200, get("/client-rule/catalogue", this.jwt.viewer()).status());
        assertEquals(403, get("/client-rule/catalogue", this.jwt.proxy()).status(), "the catalogue is for the editor");
    }

    @Test
    void aReportedCatalogueIsReadBackWithoutRareBrands() {
        Map<String, Object> snapshot = Map.of("proxyId", "Proxy-2",
                "brands", List.of(Map.of("name", "fabric", "players", 5), Map.of("name", "lonely", "players", 1)),
                "channels", List.of(), "commands", List.of("hub"), "protocols", List.of(Map.of("version", 767, "name", "1.21")));
        put("/client-rule/catalogue", snapshot, this.jwt.proxy());

        Map<String, Object> catalogue = get("/client-rule/catalogue", this.jwt.viewer()).json();

        assertEquals(true, catalogue.get("available"));
        assertTrue(catalogue.get("brands").toString().contains("fabric") && !catalogue.get("brands").toString().contains("lonely"), catalogue.toString());
    }
}
