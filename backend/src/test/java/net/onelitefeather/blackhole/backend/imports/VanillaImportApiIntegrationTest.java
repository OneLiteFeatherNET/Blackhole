package net.onelitefeather.blackhole.backend.imports;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.elo.EloScoreRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class VanillaImportApiIntegrationTest extends ApiIntegrationTest {

    private static final String CREATED = "2026-01-01 10:00:00 +0000";

    private static UUID player(String name) {
        return UUID.nameUUIDFromBytes(("import-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String owner(String name) {
        return UUIDHasher.hash(player(name));
    }

    private static String entry(String name, String reason) {
        return "{\"uuid\":\"" + player(name) + "\",\"name\":\"" + name + "\",\"created\":\"" + CREATED
                + "\",\"source\":\"Console\",\"expires\":\"forever\",\"reason\":\"" + reason + "\"}";
    }

    private static String file(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    private void givenTemplate(String key, String section, String reason) {
        Map<String, Object> body = Map.of("key", key, "reason", reason, "section", section,
                "steps", List.of(Map.of("duration", "P7D", "eloCost", 100)));
        assertEquals(200, post("/template", body).status());
    }

    /** The JDK client is the one that can send multipart; the Micronaut test client cannot. */
    private Reply importBans(String players, String templateKey, String reasonTemplates, boolean dryRun, String token) {
        String boundary = "blackhole-test-boundary";
        StringBuilder body = new StringBuilder();
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"bannedPlayers\"; filename=\"banned-players.json\"\r\n")
                .append("Content-Type: application/json\r\n\r\n").append(players).append("\r\n");
        if (reasonTemplates != null) {
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"reasonTemplates\"\r\n")
                    .append("Content-Type: text/plain\r\n\r\n").append(reasonTemplates).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        String path = "/admin/import/vanilla?dryRun=" + dryRun + (templateKey == null ? "" : "&templateKey=" + templateKey);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(this.server.getURL() + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), this.context.getBean(io.micronaut.json.JsonMapper.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> activeNetwork(String name) {
        Map<String, Object> profile = get("/profile/" + owner(name)).json();
        return (Map<String, Object>) ((Map<String, Object>) profile.get("active")).get("network");
    }

    @Test
    void everyImportedBanIsLinkedToTheTemplateAndKeepsTheOriginalReason() {
        givenTemplate("imp-link", "network", "Cheating");

        Reply reply = importBans(file(entry("link-a", "Hacked clients"), entry("link-b", "")), "imp-link", null, false, this.jwt.admin());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(2, reply.json().get("playersImported"));
        Map<String, Object> punishment = activeNetwork("link-a");
        @SuppressWarnings("unchecked") Map<String, Object> template = (Map<String, Object>) punishment.get("template");
        assertEquals("imp-link", template.get("key"), "the ban is linked to the template: " + punishment);
        assertEquals("network", punishment.get("section"), "the section comes from the template");
        @SuppressWarnings("unchecked") Map<String, Object> metaData = (Map<String, Object>) punishment.get("metaData");
        assertEquals("Hacked clients", metaData.get("importedReason"), "the original vanilla reason is kept for staff");
        assertEquals("Imported vanilla ban", ((Map<?, ?>) activeNetwork("link-b").get("metaData")).get("importedReason"),
                "a blank reason is kept as the default text");
        assertEquals(true, punishment.get("permanent"), "a forever ban stays permanent");
        assertNull(punishment.get("stepIndex"), "an import is not an escalation step");
        assertNull(punishment.get("escalationGroup"));
    }

    @Test
    void anImportChangesNoScore() {
        givenTemplate("imp-noelo", "network", "Cheating");

        importBans(file(entry("noelo-a", "x")), "imp-noelo", null, false, this.jwt.admin());

        assertTrue(this.context.getBean(EloScoreRepository.class).findByIdOwner(owner("noelo-a")).isEmpty(),
                "the template's elo cost is not deducted for a record of an earlier ban");
    }

    @Test
    void theImportIsRecordedInTheCaseTimelineAsAnAppliedPunishmentWithoutAStep() {
        givenTemplate("imp-case", "network", "Cheating");
        importBans(file(entry("case-a", "x")), "imp-case", null, false, this.jwt.admin());
        String id = (String) activeNetwork("case-a").get("identifier");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) get("/case/" + id + "/timeline").json().get("items");

        assertEquals(List.of("punishment.applied"), items.stream().map(item -> item.get("type")).toList());
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) items.getFirst().get("payload");
        assertNull(payload.get("stepIndex"));
        assertNotNull(payload.get("templateId"));
    }

    @Test
    void aMappingLinksABanOfAKnownReasonToAnotherTemplate() {
        givenTemplate("imp-default", "network", "Default reason");
        givenTemplate("imp-mapped", "network", "Mapped reason");

        Reply reply = importBans(file(entry("map-a", "Griefing"), entry("map-b", "Other")), "imp-default",
                "{\"Griefing\":\"imp-mapped\"}", false, this.jwt.admin());

        assertEquals(200, reply.status(), reply.body());
        assertEquals("imp-mapped", ((Map<?, ?>) activeNetwork("map-a").get("template")).get("key"));
        assertEquals("imp-default", ((Map<?, ?>) activeNetwork("map-b").get("template")).get("key"), "other reasons use the default");
    }

    @Test
    void aDryRunShowsTheTemplateOfEveryReasonAndWritesNothing() {
        givenTemplate("imp-dry", "network", "Dry reason");

        Reply reply = importBans(file(entry("dry-a", "Spam"), entry("dry-b", "Spam")), "imp-dry", null, true, this.jwt.admin());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(2, reply.json().get("playersImported"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> groups = (List<Map<String, Object>>) reply.json().get("reasonGroups");
        assertEquals(List.of(Map.of("reason", "Spam", "templateKey", "imp-dry", "count", 2)), groups);
        assertEquals(404, get("/profile/" + owner("dry-a")).status(), "nothing was written");
    }

    @Test
    void aTemplateInAMuteSectionIsRejectedAndNothingIsWritten() {
        givenTemplate("imp-mute", "chat", "Chat reason");

        Reply reply = importBans(file(entry("mute-a", "x")), "imp-mute", null, false, this.jwt.admin());

        assertEquals(400, reply.status(), reply.body());
        assertTrue(String.valueOf(reply.json().get("errorMessage")).contains("mute"), reply.body());
        assertEquals(404, get("/profile/" + owner("mute-a")).status(), "no profile was created");
    }

    @Test
    void aMappedTemplateInAMuteSectionRejectsTheWholeImport() {
        givenTemplate("imp-ok", "network", "Ok");
        givenTemplate("imp-mute-map", "chat", "Chat reason");

        Reply reply = importBans(file(entry("mutemap-a", "x")), "imp-ok", "{\"x\":\"imp-mute-map\"}", false, this.jwt.admin());

        assertEquals(400, reply.status(), reply.body());
        assertEquals(404, get("/profile/" + owner("mutemap-a")).status());
    }

    @Test
    void anUnknownTemplateIsNotFound() {
        Reply reply = importBans(file(entry("unknown-a", "x")), "imp-no-such", null, false, this.jwt.admin());

        assertEquals(404, reply.status(), reply.body());
    }

    @Test
    void aMalformedMappingIsABadRequest() {
        givenTemplate("imp-bad-map", "network", "Ok");

        Reply reply = importBans(file(entry("badmap-a", "x")), "imp-bad-map", "[1,2]", false, this.jwt.admin());

        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void theTemplateIsRequired() {
        Reply reply = importBans(file(entry("required-a", "x")), null, null, false, this.jwt.admin());

        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void importingTheSameFileTwiceCreatesNoDuplicates() {
        givenTemplate("imp-twice", "network", "Twice");
        String players = file(entry("twice-a", "x"));
        importBans(players, "imp-twice", null, false, this.jwt.admin());

        Reply second = importBans(players, "imp-twice", null, false, this.jwt.admin());

        assertEquals(200, second.status(), second.body());
        assertEquals(0, second.json().get("playersImported"));
        assertEquals(1, second.json().get("playersSkippedExisting"));
    }

    @Test
    void onlyAnAdminMayImport() {
        givenTemplate("imp-roles", "network", "Roles");
        String players = file(entry("roles-a", "x"));

        assertEquals(403, importBans(players, "imp-roles", null, false, this.jwt.moderator()).status());
        assertEquals(403, importBans(players, "imp-roles", null, false, this.jwt.viewer()).status());
        assertEquals(404, get("/profile/" + owner("roles-a")).status(), "nothing was written");
    }
}
