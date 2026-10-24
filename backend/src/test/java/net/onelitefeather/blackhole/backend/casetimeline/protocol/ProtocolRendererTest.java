package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.APPEAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model and both renderings, with no database, clock or network: events in, bytes out. */
class ProtocolRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long GENERATED_AT = 5_000;

    private static ProtocolSource.Appeal decidedAppeal() {
        return ProtocolFixtures.appeal(APPEAL, AppealStatus.DENIED, 1_000, 4_000);
    }

    private static ProtocolModel model(List<CaseReader.Entry> entries) {
        return ProtocolModel.build(decidedAppeal(), List.of(decidedAppeal()), entries);
    }

    private static String text(ProtocolModel model) {
        return new String(ProtocolRenderer.render(model, 1, GENERATED_AT).text(), StandardCharsets.UTF_8);
    }

    private static JsonNode json(ProtocolModel model) throws Exception {
        return JSON.readTree(ProtocolRenderer.render(model, 1, GENERATED_AT).json());
    }

    @Test
    void theSameEventsRenderByteIdentical() {
        ProtocolModel first = model(ProtocolFixtures.simpleConversation());
        ProtocolModel second = model(ProtocolFixtures.simpleConversation());

        ProtocolRenderer.Rendered a = ProtocolRenderer.render(first, 1, GENERATED_AT);
        ProtocolRenderer.Rendered b = ProtocolRenderer.render(second, 1, GENERATED_AT);

        assertArrayEquals(a.text(), b.text(), "text is deterministic");
        assertArrayEquals(a.json(), b.json(), "json is deterministic");
        assertEquals(first.digest(), second.digest());
    }

    @Test
    void onlyTheGenerationTimeDiffersBetweenTwoRuns() {
        ProtocolModel model = model(ProtocolFixtures.simpleConversation());

        String early = new String(ProtocolRenderer.render(model, 1, 5_000).text(), StandardCharsets.UTF_8);
        String late = new String(ProtocolRenderer.render(model, 1, 9_000).text(), StandardCharsets.UTF_8);

        assertNotEquals(early, late);
        assertEquals(early.replaceAll("Generated: .*", "Generated: X"), late.replaceAll("Generated: .*", "Generated: X"));
    }

    @Test
    void entriesAreInSeqOrderWithAuthorLabels() throws Exception {
        ProtocolModel model = model(ProtocolFixtures.simpleConversation());

        JsonNode entries = json(model).get("entries");

        assertEquals(4, entries.size());
        assertEquals(List.of(1L, 2L, 3L, 4L), java.util.stream.StreamSupport.stream(entries.spliterator(), false).map(e -> e.get("seq").asLong()).toList());
        assertEquals("Player", entries.get(1).get("author").get("label").asText(), "a player is shown under the case's pseudonym");
        assertEquals("Reviewer Rita", entries.get(2).get("author").get("label").asText(), "verified staff by display name");
        assertEquals("PLAYER", entries.get(1).get("visibility").asText());
    }

    @Test
    void anonymisedUnverifiedAndBotAuthorsAreLabelledByKind() throws Exception {
        List<CaseReader.Entry> entries = new ArrayList<>(List.of(
                ProtocolFixtures.filed(1, 1_000, APPEAL),
                ProtocolFixtures.staffMessage(2, 2_000, "m-1", APPEAL, "Hello", null),
                ProtocolFixtures.botMessage(3, 3_000, "m-2", APPEAL, "Ticket opened", "participant-7", Map.of()),
                ProtocolFixtures.decided(4, 4_000, APPEAL, "DENIED")));

        JsonNode shown = json(model(entries)).get("entries");

        assertEquals(ProtocolModel.ANONYMOUS_STAFF_LABEL, shown.get(1).get("author").get("label").asText(), "an erased staff name is anonymous");
        assertEquals("Bot", shown.get(2).get("author").get("label").asText());
        assertEquals("participant-7", shown.get(2).get("author").get("participantId").asText(), "the opaque participant id is in the JSON");
        assertEquals("STAFF", shown.get(2).get("visibility").asText(), "a staff-only message is listed with its visibility");
    }

    @Test
    void noBanIdDiscordIdOrOwnerHashReachesEitherRendering() {
        String banId = "K7QX-4MZP-9T";
        String discordId = "812345678901234567";
        Map<String, Object> hidden = new LinkedHashMap<>();
        hidden.put("banId", banId);
        hidden.put("discordUserId", discordId);
        hidden.put("owner", ProtocolFixtures.OWNER);
        hidden.put("attachments", List.of(Map.of("name", "proof.png", "contentType", "image/png", "size", 12, "url", "https://cdn.example/" + discordId,
                "uploader", discordId)));
        List<CaseReader.Entry> entries = new ArrayList<>(ProtocolFixtures.simpleConversation());
        entries.add(ProtocolFixtures.botMessage(5, 4_500, "m-3", APPEAL, "see attachment", "participant-1", hidden));
        entries.add(ProtocolFixtures.raw(6, 4_600, "ban_id.leak_marked", ActorTrust.VERIFIED,
                Map.of("reason", "OTHER", "source", "STAFF", "afterFiling", true, "banId", banId, "owner", ProtocolFixtures.OWNER)));

        ProtocolModel model = model(entries);
        String text = text(model);
        String json = new String(ProtocolRenderer.render(model, 1, GENERATED_AT).json(), StandardCharsets.UTF_8);

        for (String forbidden : List.of(banId, discordId, ProtocolFixtures.OWNER, "cdn.example")) {
            assertFalse(text.contains(forbidden), "text must not contain " + forbidden);
            assertFalse(json.contains(forbidden), "json must not contain " + forbidden);
        }
        assertFalse(json.matches("(?s).*[0-9a-f]{128}.*"), "no 128 hex owner-shaped value");
    }

    @Test
    void anEditedAndDeletedMessageShowsEveryTextTheEditTimeAndTheDeletion() throws Exception {
        List<CaseReader.Entry> entries = List.of(
                ProtocolFixtures.filed(1, 1_000, APPEAL),
                ProtocolFixtures.staffMessage(2, 2_000, "m-1", APPEAL, "First text", "Reviewer Rita"),
                ProtocolFixtures.edited(3, 2_500, "m-1", "Second text", "Reviewer Rita"),
                ProtocolFixtures.deleted(4, 2_800, "m-1", "Reviewer Rita"),
                ProtocolFixtures.decided(5, 4_000, APPEAL, "DENIED"));

        ProtocolModel model = model(entries);
        JsonNode message = json(model).get("entries").get(1);
        String text = text(model);

        assertEquals("Second text", message.get("text").asText(), "the last text is kept");
        assertEquals(2, message.get("texts").size());
        assertEquals("First text", message.get("texts").get(0).get("text").asText());
        assertEquals("1970-01-01T00:00:02.500Z", message.get("texts").get(1).get("at").asText());
        assertEquals("1970-01-01T00:00:02.800Z", message.get("deletedAt").asText());
        assertTrue(text.contains("First text") && text.contains("(first text)") && text.contains("Current text since #3  1970-01-01T00:00:02.500Z") && text.contains("Deleted at 1970-01-01T00:00:02.800Z"), text);
    }

    @Test
    void anAttachmentShowsNameTypeAndSizeButNoUrlOrBytes() {
        Map<String, Object> extra = Map.of("attachments", List.of(Map.of("name", "screen.png", "contentType", "image/png", "size", 2048,
                "url", "https://cdn.discordapp.com/attachments/1/2/screen.png")));
        List<CaseReader.Entry> entries = List.of(
                ProtocolFixtures.filed(1, 1_000, APPEAL),
                ProtocolFixtures.botMessage(2, 2_000, "m-1", APPEAL, "look", "participant-1", extra),
                ProtocolFixtures.decided(3, 4_000, APPEAL, "DENIED"));

        ProtocolModel model = model(entries);
        String text = text(model);

        assertTrue(text.contains("Attachment: screen.png (image/png, 2048 bytes)"), text);
        assertFalse(text.contains("discordapp"), "no CDN url");
        assertFalse(new String(ProtocolRenderer.render(model, 1, GENERATED_AT).json(), StandardCharsets.UTF_8).contains("discordapp"));
    }

    @Test
    void aRedactedMessageIsAMarkerWithoutAnyText() throws Exception {
        CaseReader.Entry added = ProtocolFixtures.playerMessage(2, 2_000, "m-1", APPEAL, "my secret address");
        CaseReader.Entry edit = ProtocolFixtures.edited(3, 2_500, "m-1", "my secret street", "Reviewer Rita");
        List<CaseReader.Entry> entries = List.of(
                ProtocolFixtures.filed(1, 1_000, APPEAL),
                ProtocolFixtures.redacted(added), ProtocolFixtures.redacted(edit),
                ProtocolFixtures.redactedMarker(4, 3_000, "m-1"),
                ProtocolFixtures.decided(5, 4_000, APPEAL, "DENIED"));

        ProtocolModel model = model(entries);
        JsonNode message = json(model).get("entries").get(1);
        String text = text(model);

        assertTrue(message.get("redacted").asBoolean());
        assertTrue(message.get("text").isNull());
        assertTrue(message.get("texts").get(0).get("text").isNull());
        assertTrue(text.contains(ProtocolRenderer.REMOVED), text);
        assertFalse(text.contains("secret"));
    }

    @Test
    void theEntryCountAndDigestCanBeRecomputedFromTheJson() throws Exception {
        ProtocolModel model = model(ProtocolFixtures.simpleConversation());

        JsonNode document = json(model);
        List<String> lines = new ArrayList<>();
        for (JsonNode event : document.get("source")) {
            lines.add(event.get("eventId").asText() + "|" + event.get("seq").asLong() + "|" + event.get("type").asText() + "|"
                    + (event.get("redacted").asBoolean() ? 1 : 0));
        }
        JsonNode completeness = document.get("completeness");

        assertEquals(document.get("entries").size(), completeness.get("entryCount").asInt());
        assertEquals(ProtocolModel.sha256(String.join("\n", lines)), completeness.get("sourceDigest").asText());
        assertEquals(1, completeness.get("firstSeq").asLong());
        assertEquals(4, completeness.get("lastSeq").asLong());
        assertEquals(2, document.get("schemaVersion").asInt(), "entries gained a format, so the layout is version 2");
        assertTrue(text(model).contains("Source digest (SHA-256): " + model.digest()));
    }

    @Test
    void aRedactionChangesTheDigest() {
        CaseReader.Entry added = ProtocolFixtures.playerMessage(2, 2_000, "m-1", APPEAL, "text");
        ProtocolModel before = model(List.of(ProtocolFixtures.filed(1, 1_000, APPEAL), added, ProtocolFixtures.decided(3, 4_000, APPEAL, "DENIED")));
        ProtocolModel after = model(List.of(ProtocolFixtures.filed(1, 1_000, APPEAL), ProtocolFixtures.redacted(added),
                ProtocolFixtures.decided(3, 4_000, APPEAL, "DENIED")));

        assertNotEquals(before.digest(), after.digest());
    }

    @Test
    void notesAndPunishmentEventsAreNotPartOfTheProtocol() {
        List<CaseReader.Entry> entries = List.of(
                ProtocolFixtures.raw(1, 500, "punishment.applied", ActorTrust.SYSTEM, Map.of("permanent", false, "escalated", false)),
                ProtocolFixtures.filed(2, 1_000, APPEAL),
                ProtocolFixtures.note(3, 1_500, "internal: looks guilty"),
                ProtocolFixtures.decided(4, 4_000, APPEAL, "DENIED"));

        ProtocolModel model = model(entries);

        assertEquals(2, model.entryCount());
        assertFalse(text(model).contains("looks guilty"));
    }

    @Test
    void aMessageBelongsToTheAppealItNamesOrTheOneFiledBeforeIt() {
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        ProtocolSource.Appeal first = ProtocolFixtures.appeal(APPEAL, AppealStatus.DENIED, 1_000, 4_000);
        ProtocolSource.Appeal other = ProtocolFixtures.appeal(second, AppealStatus.DENIED, 10_000, 12_000);
        List<CaseReader.Entry> entries = List.of(
                ProtocolFixtures.filed(1, 1_000, APPEAL),
                ProtocolFixtures.staffMessage(2, 2_000, "m-1", null, "case level, first window", "Reviewer Rita"),
                ProtocolFixtures.decided(3, 4_000, APPEAL, "DENIED"),
                ProtocolFixtures.filed(4, 10_000, second),
                ProtocolFixtures.playerMessage(5, 11_000, "m-2", second, "second appeal"),
                ProtocolFixtures.staffMessage(6, 11_500, "m-3", null, "case level, second window", "Reviewer Rita"),
                ProtocolFixtures.decided(7, 12_000, second, "DENIED"));

        ProtocolModel one = ProtocolModel.build(first, List.of(first, other), entries);
        ProtocolModel two = ProtocolModel.build(other, List.of(first, other), entries);

        assertEquals(List.of(1L, 2L, 3L), one.entries().stream().map(ProtocolModel.Entry::seq).toList());
        assertEquals(List.of(4L, 5L, 6L, 7L), two.entries().stream().map(ProtocolModel.Entry::seq).toList());
    }

    @Test
    void aDiscordTicketShowsItsCompletenessProofInTheFooter() throws Exception {
        List<CaseReader.Entry> entries = new ArrayList<>(ProtocolFixtures.simpleConversation());
        entries.add(ProtocolFixtures.raw(5, 4_200, "ticket.transcript_complete", ActorTrust.SYSTEM,
                Map.of("ticketId", "t-1", "count", 14, "lastMessageId", "990000000000000001", "edits", 1, "deletes", 0)));

        ProtocolModel model = model(entries);

        assertFalse(model.ticketIncomplete());
        assertTrue(text(model).contains("Discord ticket transcript: complete, 14 messages, last message id 990000000000000001"), text(model));
        JsonNode ticket = json(model).get("completeness").get("ticket");
        assertEquals(14, ticket.get("messageCount").asInt());
        assertEquals("990000000000000001", ticket.get("lastMessageId").asText());
    }

    @Test
    void aPortalOnlyAppealHasNoTicketLine() throws Exception {
        ProtocolModel model = model(ProtocolFixtures.simpleConversation());

        assertNull(model.ticket());
        assertFalse(text(model).contains("Discord ticket"));
        assertNull(json(model).get("completeness").get("ticket"));
    }

    @Test
    void aTicketWithoutACompletenessProofIsIncomplete() {
        List<CaseReader.Entry> entries = new ArrayList<>(ProtocolFixtures.simpleConversation());
        entries.add(ProtocolFixtures.raw(5, 1_500, "ticket.opened", ActorTrust.SYSTEM, Map.of("ticketId", "t-1")));

        assertTrue(model(entries).ticketIncomplete());
    }

    @Test
    void aMessageTextIsTheMarkdownSourceInBothFilesAndTheJsonSaysSo() throws Exception {
        String source = "**bold** and [a link](https://example.org/a?b=1)\n- item\n<b>x</b>";
        ProtocolModel model = model(List.of(ProtocolFixtures.filed(1, 1_000, APPEAL), ProtocolFixtures.playerMessage(2, 2_000, "m-1", APPEAL, source),
                ProtocolFixtures.decided(3, 4_000, APPEAL, "DENIED")));

        JsonNode message = json(model).get("entries").get(1);

        assertEquals(source, message.get("text").asText(), "the JSON keeps the source, not a rendering");
        assertEquals("markdown", message.get("format").asText(), "a reader knows how to read the text");
        String text = text(model);
        assertTrue(text.contains("    **bold** and [a link](https://example.org/a?b=1)\n    - item\n    <b>x</b>"), "the text file keeps the source: " + text);
    }

    @Test
    void anEventEntryHasNoFormat() throws Exception {
        ProtocolModel model = model(ProtocolFixtures.simpleConversation());

        assertNull(json(model).get("entries").get(0).get("format"), "only messages hold Markdown");
    }

    @Test
    void everyEditedTextIsMarkdownToo() throws Exception {
        ProtocolModel model = model(List.of(ProtocolFixtures.filed(1, 1_000, APPEAL), ProtocolFixtures.playerMessage(2, 2_000, "m-1", APPEAL, "*one*"),
                ProtocolFixtures.edited(3, 3_000, "m-1", "*two*", null), ProtocolFixtures.decided(4, 4_000, APPEAL, "DENIED")));

        JsonNode message = json(model).get("entries").get(1);

        assertEquals("markdown", message.get("format").asText());
        assertEquals("*one*", message.get("texts").get(0).get("text").asText(), "earlier versions are sources as well");
        assertEquals("*two*", message.get("text").asText());
    }
}
