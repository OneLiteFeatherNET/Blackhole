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
                new CaseEventPayload.TranscriptExported("json", 12, false));
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

        assertInstanceOf(CaseEventPayload.Unknown.class, CaseEventPayloads.read(CaseEventPayload.MESSAGE_ADDED, 2, body));
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
    void thePortalAllowListNamesMessagesAndMarkersButNotNotes() {
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("message.added"));
        assertTrue(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("ban_id.leak_marked"));
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("note.added"));
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("transcript.exported"));
    }
}
