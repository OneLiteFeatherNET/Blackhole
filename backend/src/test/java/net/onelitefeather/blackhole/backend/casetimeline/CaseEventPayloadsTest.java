package net.onelitefeather.blackhole.backend.casetimeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseEventPayloadsTest {

    static Stream<CaseEventPayload> everyPayloadType() {
        return Stream.of(
                new CaseEventPayload.Applied("network", 1, 3, 40, 960, 920, false, false, "tpl", 1_000L),
                new CaseEventPayload.Applied(null, null, null, null, null, null, true, true, null, null),
                new CaseEventPayload.Revoked(false),
                new CaseEventPayload.Revoked(true),
                new CaseEventPayload.Lifted("appeal-1", false),
                new CaseEventPayload.Retemplated("tpl-2", null),
                new CaseEventPayload.Retemplated("tpl-2", "tpl-1"),
                new CaseEventPayload.Expired(2_000L),
                new CaseEventPayload.EvidenceAttached("ev-1", "ANTICHEAT", "grim"),
                new CaseEventPayload.EvidenceLifted("ev-1", "appeal-1", true),
                new CaseEventPayload.EvidenceRevoked("ev-1", false),
                new CaseEventPayload.AppealFiled("appeal-1", true),
                new CaseEventPayload.AppealDecided("appeal-1", "GRANTED_DURATION_REDUCTION", 3_000L),
                new CaseEventPayload.AppealDecided("appeal-1", "DENIED", null),
                new CaseEventPayload.StatusChanged("CLOSED", "OPEN"),
                new CaseEventPayload.AssigneeChanged(null, "6a1d3a52-0000-4000-8000-000000000001"),
                new CaseEventPayload.AssigneeChanged("6a1d3a52-0000-4000-8000-000000000001", null),
                new CaseEventPayload.PriorityChanged("NORMAL", "HIGH"),
                new CaseEventPayload.TagsChanged(java.util.List.of("a", "b"), java.util.List.of()),
                new CaseEventPayload.NoteAdded("n-1", "text"),
                new CaseEventPayload.NoteEdited("n-1", "new text"),
                new CaseEventPayload.NoteRedacted("n-1", "PERSONAL_DATA"),
                new CaseEventPayload.MessageAdded("m-1", "appeal-1", MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, "hello"),
                new CaseEventPayload.MessageAdded("m-2", null, MessageAuthorKind.EXTERNAL, "participant-1", MessageChannel.DISCORD, "hi"),
                new CaseEventPayload.MessageAdded("m-3", null, MessageAuthorKind.STAFF, null, MessageChannel.PANEL, null),
                new CaseEventPayload.MessageEdited("m-1", "new text"),
                new CaseEventPayload.MessageDeleted("m-1", MessageAuthorKind.STAFF),
                new CaseEventPayload.MessageRedacted("m-1", "PERSONAL_DATA"),
                new CaseEventPayload.BanIdLeakMarked(BanIdLeakReason.POSTED_PUBLIC, BanIdLeakSource.STAFF, true),
                new CaseEventPayload.BanIdReissued(BanIdReissueReason.NOT_PLAYERS_FAULT, 1, 2),
                new CaseEventPayload.TranscriptExported("json", 12, false),
                new CaseEventPayload.MessageAdded("m-4", "appeal-1", MessageAuthorKind.STAFF, "participant-2", MessageChannel.DISCORD, "see file",
                        java.util.List.of(new MessageAttachment(MessageAttachment.FILE, "1234567890123456789", "log.txt", "text/plain", 2048L, null, null),
                                new MessageAttachment(MessageAttachment.FILE, "1234567890123456790", "shot.png", "image/png", 4096L, 640, 480),
                                new MessageAttachment(MessageAttachment.STICKER, null, "wave", null, null, null, null)),
                        java.util.List.of(new MessageEmbed("link", "example.org"), new MessageEmbed("image", null))),
                new CaseEventPayload.MessageDeleted("m-5", MessageAuthorKind.EXTERNAL, true, false),
                new CaseEventPayload.MessageDeleted("m-6", MessageAuthorKind.PLAYER, false, true),
                new CaseEventPayload.BanIdLeakMarked(BanIdLeakReason.POSTED_PUBLIC, BanIdLeakSource.DISCORD_BOT, true, "participant-1", true),
                new CaseEventPayload.TicketOpened("ticket-1", "appeal-1", 42L),
                new CaseEventPayload.TicketTranscriptComplete("ticket-1", "appeal-1", 12, "1234567890123456789", 2, 1),
                new CaseEventPayload.TicketTranscriptComplete("ticket-2", "appeal-2", 0, null, 0, 0),
                new CaseEventPayload.TicketArchived("ticket-1", "appeal-1", 3),
                new CaseEventPayload.TicketChannelDeleted("ticket-1", "appeal-1", true, null),
                new CaseEventPayload.TicketChannelDeleted("ticket-3", "appeal-3", false, 5_000L),
                new CaseEventPayload.TicketFrozen("ticket-1", "appeal-1", true, "STAFF"),
                new CaseEventPayload.TicketFrozen("ticket-1", "appeal-1", false, null));
    }

    @ParameterizedTest
    @MethodSource("everyPayloadType")
    void aPayloadSurvivesBeingStoredAndRead(CaseEventPayload payload) {
        Map<String, Object> stored = CaseEventPayloads.parse(CaseEventPayloads.toJson(payload));

        CaseEventPayload read = CaseEventPayloads.read(payload.type(), payload.version(), stored);

        assertEquals(payload, read, "the " + payload.type() + " payload must read back as written");
    }

    @Test
    void aTypeThisReleaseDoesNotKnowIsKeptAsAnOpaquePayload() {
        Map<String, Object> body = Map.of("messageId", "m-1");

        CaseEventPayload read = CaseEventPayloads.read("message.added", 1, body);

        CaseEventPayload.Unknown unknown = assertInstanceOf(CaseEventPayload.Unknown.class, read);
        assertEquals("message.added", unknown.type());
        assertEquals(body, unknown.body(), "the body is kept, not dropped");
    }

    @Test
    void aNewerPayloadVersionOfAKnownTypeIsKeptAsAnOpaquePayload() {
        Map<String, Object> body = Map.of("expiredAt", 5, "reason", "added by a newer release");

        CaseEventPayload read = CaseEventPayloads.read(CaseEventPayload.EXPIRED, 2, body);

        CaseEventPayload.Unknown unknown = assertInstanceOf(CaseEventPayload.Unknown.class, read);
        assertEquals(2, unknown.version());
        assertEquals(body, unknown.body());
    }

    @Test
    void aBodyThatDoesNotMatchItsTypeIsKeptAsAnOpaquePayloadInsteadOfFailing() {
        CaseEventPayload read = CaseEventPayloads.read(CaseEventPayload.APPEAL_FILED, 1, Map.of("eligible", true));

        assertInstanceOf(CaseEventPayload.Unknown.class, read, "the appeal id is missing");
    }

    @Test
    void aRedactedPayloadIsReadWithoutFailing() {
        CaseEventPayload read = CaseEventPayloads.read("note.added", 1, CaseEventPayloads.parse(null));

        assertTrue(assertInstanceOf(CaseEventPayload.Unknown.class, read).body().isEmpty(), "nothing is left to show");
    }

    @Test
    void anAppealFiledPayloadHoldsNoStatementText() {
        Map<String, Object> body = new CaseEventPayload.AppealFiled("appeal-1", true).toMap();

        assertEquals(Map.of("appealId", "appeal-1", "eligible", true), body, "ids and flags only");
    }

    @Test
    void aMessageBodyWithoutItsAuthorIsKeptOpaque() {
        assertInstanceOf(CaseEventPayload.Unknown.class, CaseEventPayloads.read("ban_id.leak_marked", 1, Map.of()));
        assertInstanceOf(CaseEventPayload.Unknown.class, CaseEventPayloads.read("message.added", 1, Map.of("messageId", "m-1")));
    }

    @Test
    void aMessageOfAFutureVersionIsKeptOpaque() {
        Map<String, Object> body = Map.of("messageId", "m-1", "author", Map.of("kind", "PLAYER"), "channel", "PORTAL", "text", "x");

        assertInstanceOf(CaseEventPayload.Unknown.class, CaseEventPayloads.read(CaseEventPayload.MESSAGE_ADDED, 3, body));
    }

    @Test
    void aRedactedMessageStillReadsAsAMessageWithoutText() {
        Map<String, Object> body = Map.of("messageId", "m-1", "author", Map.of("kind", "PLAYER"), "channel", "PORTAL");

        CaseEventPayload.MessageAdded read = assertInstanceOf(CaseEventPayload.MessageAdded.class,
                CaseEventPayloads.read(CaseEventPayload.MESSAGE_ADDED, 1, body));

        assertNull(read.text());
        assertEquals(MessageAuthorKind.PLAYER, read.authorKind(), "the author kind stays after a redaction");
    }

    @Test
    void theLeakAndReissuePayloadsHoldCodesAndNumbersOnly() {
        assertEquals(Map.of("reason", "SHARED", "source", "STAFF", "afterFiling", false),
                new CaseEventPayload.BanIdLeakMarked(BanIdLeakReason.SHARED, BanIdLeakSource.STAFF, false).toMap());
        assertEquals(Map.of("reason", "OTHER", "fromGeneration", 3, "toGeneration", 4),
                new CaseEventPayload.BanIdReissued(BanIdReissueReason.OTHER, 3, 4).toMap());
    }

    @Test
    void aMessageWithoutAttachmentsStoresNoAttachmentKeys() {
        Map<String, Object> body = new CaseEventPayload.MessageAdded("m", null, MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, "x").toMap();

        assertFalse(body.containsKey("attachments"), "the portal and panel payload is unchanged");
        assertFalse(body.containsKey("embeds"));
    }

    @Test
    void attachmentMetadataHoldsNoUrl() {
        MessageAttachment image = new MessageAttachment(MessageAttachment.FILE, "9", "shot.png", "image/png", 10L, 1, 2);

        assertEquals(Map.of("kind", "file", "id", "9", "name", "shot.png", "contentType", "image/png", "size", 10L, "width", 1, "height", 2),
                image.toMap(), "id, name, type, size and dimensions only");
    }

    @Test
    void aNormalDeleteStoresNoMarkers() {
        assertEquals(Map.of("messageId", "m", "deletedBy", "STAFF"), new CaseEventPayload.MessageDeleted("m", MessageAuthorKind.STAFF).toMap());
    }

    @Test
    void ticketPayloadsHoldIdsAndCountsOnly() {
        assertEquals(Map.of("ticketId", "t", "appealId", "a", "count", 3, "lastMessageId", "99", "edits", 1, "deletes", 0),
                new CaseEventPayload.TicketTranscriptComplete("t", "a", 3, "99", 1, 0).toMap());
    }

    @Test
    void thePortalAllowListShowsTicketOpenedAndClosedButNotArchivedOrDeleted() {
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("ticket.opened"));
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("ticket.transcript_complete"));
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("ticket.archived"));
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("ticket.channel_deleted"));
    }

    @Test
    void thePortalAllowListNamesMessagesAndMarkersButNotNotes() {
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("message.added"));
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("ban_id.leak_marked"));
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("note.added"));
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("transcript.exported"));
    }

    @Test
    void thePortalAllowListShowsSharesAndRevokesButNotEvidenceAttached() {
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("evidence.shared"));
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("evidence.share_revoked"));
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("evidence.attached"), "a player never learns what staff attached");
    }

    @Test
    void aShareAndARevokeRoundTripWithIdsKindsAndCountsOnly() {
        CaseEventPayload.EvidenceShared shared = new CaseEventPayload.EvidenceShared("s-1", "CHAT_LOG", "EXCERPT", 5);
        CaseEventPayload.EvidenceShareRevoked revoked = new CaseEventPayload.EvidenceShareRevoked("s-1", "PRIVACY");

        assertEquals(shared, CaseEventPayloads.read(shared.type(), 1, shared.toMap()));
        assertEquals(revoked, CaseEventPayloads.read(revoked.type(), 1, revoked.toMap()));
        assertEquals(java.util.Set.of("shareId", "evidenceKind", "mode", "lineCount"), shared.toMap().keySet());
        assertEquals(java.util.Set.of("shareId", "reason"), revoked.toMap().keySet());
    }

    @Test
    void aShareOfAScreenshotHasNoLineCount() {
        CaseEventPayload.EvidenceShared shared = new CaseEventPayload.EvidenceShared("s-2", "FILE", "FILE", null);

        assertFalse(shared.toMap().containsKey("lineCount"));
        assertEquals(shared, CaseEventPayloads.read(shared.type(), 1, shared.toMap()));
    }

    @Test
    void aChatFileEntryOfAMessageRoundTrips() {
        CaseEventPayload.MessageAdded message = new CaseEventPayload.MessageAdded("m-1", null, MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, "t",
                java.util.List.of(new MessageAttachment("file", null, "a.png", "image/png", 10L, 3, 2, "11111111-1111-1111-1111-111111111111", "PLAYER")),
                java.util.List.of());

        CaseEventPayload.MessageAdded read = (CaseEventPayload.MessageAdded) CaseEventPayloads.read(message.type(), 1, message.toMap());

        assertEquals(message, read);
        assertEquals("PLAYER", read.attachments().getFirst().provenance());
    }
}
