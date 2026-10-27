package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /public/appeal/protocol}: the player's own copy of a decided appeal, rendered on demand from the
 * player-visible entries. The stored staff protocol and every staff-only entry never reach a player session.
 */
@Tag("integration")
class PublicProtocolIntegrationTest extends PublicApiTestBase {

    private static final UUID REVIEWER = UUID.fromString("b0b00000-0000-4000-8000-000000000031");

    @TempDir
    Path files;

    @Override
    protected Map<String, Object> extraProperties() {
        Map<String, Object> properties = new HashMap<>(super.extraProperties());
        properties.put("blackhole.evidence.files.storage", "local");
        properties.put("blackhole.evidence.files.local.path", this.files.toString());
        properties.put("blackhole.evidence.scan.enabled", false);
        properties.put("blackhole.protocol.reconcile-interval", "0");
        return properties;
    }

    private String reviewer() {
        return named(this.jwt.delegated().groups(net.onelitefeather.blackhole.backend.security.TestJwtIssuer.MODERATOR_GROUP)
                .claim("oid", REVIEWER.toString()).claim("name", "Reviewer Rita Secretname").sign());
    }

    @SuppressWarnings("unchecked")
    private String appealIdOf(String punishment) {
        List<Map<String, Object>> all = (List<Map<String, Object>>) get("/appeal?size=100").json().get("content");
        return (String) all.stream().filter(a -> punishment.equals(a.get("punishmentIdentifier"))).findFirst().orElseThrow().get("identifier");
    }

    /** A decided appeal with a player message, a staff note, a team reply and an internal decision note. */
    private String decidedAppeal(String owner, String key) {
        String punishment = punish(owner, key);
        String token = sessionFor(punishment);
        fileAppeal(token);
        assertEquals(200, playerPost("/public/messages", token, Map.of("text", "PLAYER-WORDS I did not do it.")).status());
        assertEquals(200, post("/case/" + punishment + "/notes", Map.of("text", "SECRET-STAFF-NOTE"), named(this.jwt.moderator())).status());
        Reply review = post("/appeal/" + appealIdOf(punishment) + "/review", Map.of("decision", "DENIED", "decisionNote", "INTERNAL-NOTE-XYZ",
                "playerMessage", "TEAM-WORDS the ban stays."), reviewer());
        assertEquals(200, review.status(), review.body());
        return punishment;
    }

    @Test
    void aDecidedAppealGivesThePlayerTheirCopyAsATextAttachment() {
        String punishment = decidedAppeal("d1".repeat(64), "pub-proto-text");
        String token = sessionFor(punishment);

        Raw copy = playerGet("/public/appeal/protocol", token);

        assertEquals(200, copy.status(), copy.body());
        assertEquals("no-store", copy.header("Cache-Control"));
        assertEquals("nosniff", copy.header("X-Content-Type-Options"));
        assertTrue(copy.header("Content-Disposition").startsWith("attachment; filename=\""), copy.header("Content-Disposition"));
        assertTrue(copy.header("Content-Type").startsWith("text/plain"), copy.header("Content-Type"));
        assertTrue(copy.body().contains("PLAYER-WORDS I did not do it."), copy.body());
        assertTrue(copy.body().contains("TEAM-WORDS the ban stays."), copy.body());
        // The decision message is written under the reviewer's display name (a random "Person ..." in this test), never the generic label.
        assertTrue(copy.body().contains("You") && copy.body().contains("Person "), copy.body());
        assertFalse(copy.body().contains("Moderation team"), copy.body());
        assertFalse(copy.body().contains("Reviewer Rita Secretname"), "the Entra name never reaches the player");
        assertTrue(copy.body().contains("Appeal decided"), copy.body());
    }

    @Test
    void theJsonFormatIsAnAttachmentToo() {
        String token = sessionFor(decidedAppeal("d2".repeat(64), "pub-proto-json"));

        Raw copy = playerGet("/public/appeal/protocol?format=json", token);

        assertEquals(200, copy.status(), copy.body());
        assertTrue(copy.header("Content-Type").startsWith("application/json"), copy.header("Content-Type"));
        assertTrue(copy.header("Content-Disposition").contains(".json"));
        assertEquals("DENIED", copy.json().get("decision"));
        assertEquals("no-store", copy.header("Cache-Control"));
        assertEquals(2, copy.json().get("schemaVersion"), "entries gained a format, so the layout is version 2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) copy.json().get("entries");
        List<Map<String, Object>> messages = entries.stream().filter(entry -> "MESSAGE".equals(entry.get("kind"))).toList();
        assertFalse(messages.isEmpty(), "the decided appeal has messages");
        assertTrue(messages.stream().allMatch(entry -> "markdown".equals(entry.get("format"))), "every message text is Markdown source");
    }

    @Test
    void noStaffNameNoStaffOnlyEntryAndNoInternalNoteReachThePlayer() {
        String punishment = decidedAppeal("d3".repeat(64), "pub-proto-staff");
        String token = sessionFor(punishment);

        for (String format : List.of("text", "json")) {
            Raw copy = playerGet("/public/appeal/protocol?format=" + format, token);
            assertEquals(200, copy.status());
            assertFalse(copy.body().contains("Secretname"), "no staff name in " + format);
            assertFalse(copy.body().contains("Reviewer Rita"), "no staff name in " + format);
            assertFalse(copy.body().contains("SECRET-STAFF-NOTE"), "no staff note in " + format);
            assertFalse(copy.body().contains("INTERNAL-NOTE-XYZ"), "no decision note in " + format);
            assertFalse(copy.body().contains(punishment), "no punishment identifier in " + format);
        }
    }

    @Test
    void theStoredStaffProtocolExistsButIsNeverWhatThePlayerGets() {
        String punishment = decidedAppeal("d4".repeat(64), "pub-proto-stored");
        String token = sessionFor(punishment);

        @SuppressWarnings("unchecked") List<Map<String, Object>> stored = (List<Map<String, Object>>) get("/case/" + punishment + "/protocol", this.jwt.viewer())
                .json().get("items");
        assertEquals(1, stored.size(), "precondition: the decision stored the staff protocol");
        String staffText = (String) get("/case/" + punishment + "/protocol/" + stored.getFirst().get("evidenceId"), this.jwt.viewer()).json().get("text");
        assertTrue(staffText.contains("Reviewer Rita Secretname") && staffText.contains("Source digest"), "the staff protocol names staff and has the footer");

        String playerText = playerGet("/public/appeal/protocol", token).body();

        assertNotEquals(staffText, playerText);
        assertFalse(playerText.contains("Source digest"), playerText);
        assertFalse(playerText.contains("visibility"), playerText);
        assertFalse(playerText.contains((String) stored.getFirst().get("evidenceId")), "the stored item is not addressable from a session");
    }

    @Test
    void anUndecidedAppealIsNotDecided409() {
        String punishment = punish("d5".repeat(64), "pub-proto-open");
        String token = sessionFor(punishment);
        fileAppeal(token);

        Raw copy = playerGet("/public/appeal/protocol", token);

        assertEquals(409, copy.status());
        assertEquals("NOT_DECIDED", copy.json().get("code"));
        assertEquals("no-store", copy.header("Cache-Control"));
    }

    @Test
    void withoutAnyAppealItIsNotDecidedToo() {
        String token = sessionFor(punish("d6".repeat(64), "pub-proto-none"));

        assertEquals("NOT_DECIDED", playerGet("/public/appeal/protocol", token).json().get("code"));
    }

    @Test
    void aLeakedBanIdGetsNothing() {
        String punishment = decidedAppeal("d7".repeat(64), "pub-proto-leak");
        String token = sessionFor(punishment);
        markLeaked(punishment);

        Raw copy = playerGet("/public/appeal/protocol", token);

        assertTrue(copy.status() == 403 || copy.status() == 401, "limited or revoked: " + copy.status());
        assertFalse(copy.body().contains("PLAYER-WORDS"));
    }

    @Test
    void anUnknownFormatIs400AndNoTokenIs401() {
        String token = sessionFor(decidedAppeal("d8".repeat(64), "pub-proto-format"));

        assertEquals(400, playerGet("/public/appeal/protocol?format=pdf", token).status());
        assertEquals(401, playerGet("/public/appeal/protocol", null).status());
        assertEquals(401, call(HttpMethod.GET, "/public/appeal/protocol", "bhps_" + "A".repeat(43), null).status());
    }

    @Test
    void aStaffTokenIsRefusedOnTheRoute() {
        int status = playerGet("/public/appeal/protocol", named(this.jwt.admin())).status();
        assertTrue(status == 401 || status == 403, "a staff token is no player session, got " + status);
    }
}
