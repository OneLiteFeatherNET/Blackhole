package net.onelitefeather.blackhole.discord.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditEmbedTest {

    private static final String AUDIT_ID = "7d1c2a4e-0000-4000-8000-000000000001";
    private static final String ACTOR_OID = "9a8b7c6d-1111-4222-8333-444455556666";
    private static final String PANEL = "https://panel.example.org";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static StaffActionEvent revokeByAda() {
        return new StaffActionEvent(AUDIT_ID, 1_700_000_000_000L, "PANEL", "punishment.revoke", "Ada", ACTOR_OID,
                "CASE", "AB12CD34", null, "/cases/AB12CD34");
    }

    private static JsonNode embedOf(StaffActionEvent event) throws IOException {
        return JSON.readTree(new AuditEmbedRenderer(PANEL).render(event)).path("embeds").get(0);
    }

    private static JsonNode lastField(JsonNode embed) {
        JsonNode last = null;
        for (JsonNode field : embed.path("fields")) {
            last = field;
        }
        return last;
    }

    @Test
    void theTitleNamesTheActionInPlainWords() throws IOException {
        assertTrue(embedOf(revokeByAda()).path("title").asText().contains("Punishment revoked"), "the title says what happened");
    }

    @Test
    void theFieldsNameTheStaffMemberTheSurfaceTheTargetAndTheTime() throws IOException {
        String fields = embedOf(revokeByAda()).path("fields").toString();

        assertTrue(fields.contains("Ada"), "the staff member: " + fields);
        assertTrue(fields.contains("Panel"), "the surface: " + fields);
        assertTrue(fields.contains("AB12CD34"), "the case short id: " + fields);
        assertTrue(fields.contains("<t:1700000000:f>"), "the Discord timestamp of the time: " + fields);
    }

    @Test
    void theLastFieldLinksToThePanelPathOfTheTarget() throws IOException {
        JsonNode embed = embedOf(revokeByAda());

        assertEquals("[Open in panel · Im Panel öffnen](https://panel.example.org/cases/AB12CD34)",
                lastField(embed).path("value").asText(), "the link ends the message");
        assertEquals("https://panel.example.org/cases/AB12CD34", embed.path("url").asText(), "the title links there too");
    }

    @Test
    void theFooterCarriesTheAuditIdOfTheEntry() throws IOException {
        assertTrue(embedOf(revokeByAda()).path("footer").path("text").asText().contains(AUDIT_ID), "the id finds the row again");
    }

    @Test
    void noStaffOidAppearsAnywhereInTheMessage() {
        String message = new AuditEmbedRenderer(PANEL).render(revokeByAda());

        assertFalse(message.contains(ACTOR_OID), "the staff oid is not shown");
    }

    @Test
    void aMissingNameIsShownAsUnknownWithoutAnyIdAndAPlayerTargetShowsItsName() throws IOException {
        StaffActionEvent anonymous = new StaffActionEvent(AUDIT_ID, 1_700_000_000_000L, "API", "kick.create", null, ACTOR_OID,
                "PLAYER", "owner-hash-1", "Steve", "/players/owner-hash-1");

        String fields = embedOf(anonymous).path("fields").toString();

        assertTrue(fields.contains("Unknown"), "the missing name is named: " + fields);
        assertFalse(fields.contains(ACTOR_OID), "and no id stands in for it");
        assertTrue(fields.contains("Steve"), "a player target shows the player name");
    }

    @Test
    void mentionsAreSwitchedOffSoNoNameCanPingAnyone() throws IOException {
        JsonNode root = JSON.readTree(new AuditEmbedRenderer(PANEL).render(revokeByAda()));

        assertEquals(0, root.path("allowed_mentions").path("parse").size(), "no role or user is pinged");
    }
}
