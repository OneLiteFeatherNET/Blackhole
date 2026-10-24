package net.onelitefeather.blackhole.backend.casetimeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The current-text rule and the deleted and redacted flags, folded from plain events. */
class MessageThreadTest {

    private final List<CaseEventRecord> events = new ArrayList<>();
    private long seq = 1;

    private void add(CaseEventPayload payload, String messageId, ActorTrust trust, Long redactedAt) {
        Map<String, Object> body = CaseEventPayloads.parse(CaseEventPayloads.toJson(payload));
        if (redactedAt != null) {
            body = new java.util.HashMap<>(body);
            body.remove("text");
        }
        this.events.add(new CaseEventRecord(UUID.randomUUID(), this.seq, "p", payload.type(), 1, this.seq * 10, CaseEventOrigin.LIVE, trust,
                null, null, null, messageId, "PLAYER", body, redactedAt, null));
        this.seq++;
    }

    @Test
    void theCurrentTextIsTheLatestAddedOrEditedVersion() {
        add(new CaseEventPayload.MessageAdded("m", null, MessageAuthorKind.STAFF, null, MessageChannel.PANEL, "one"), "m", ActorTrust.VERIFIED, null);
        add(new CaseEventPayload.MessageEdited("m", "two"), "m", ActorTrust.VERIFIED, null);
        add(new CaseEventPayload.MessageEdited("m", "three"), "m", ActorTrust.VERIFIED, null);

        MessageThread.Message message = MessageThread.of(this.events).getFirst();

        assertEquals("three", message.text());
        assertEquals(2, message.edits());
        assertFalse(message.deleted());
        assertFalse(message.redacted());
    }

    @Test
    void aDeletedMessageKeepsItsLastTextAndIsMarkedDeleted() {
        add(new CaseEventPayload.MessageAdded("m", null, MessageAuthorKind.STAFF, null, MessageChannel.PANEL, "gone"), "m", ActorTrust.VERIFIED, null);
        add(new CaseEventPayload.MessageDeleted("m", MessageAuthorKind.STAFF), "m", ActorTrust.VERIFIED, null);

        MessageThread.Message message = MessageThread.of(this.events).getFirst();

        assertTrue(message.deleted());
        assertEquals("gone", message.text());
    }

    @Test
    void aRedactedVersionTakesTheTextAwayFromTheWholeMessage() {
        add(new CaseEventPayload.MessageAdded("m", null, MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, "secret"), "m", ActorTrust.PLAYER, 99L);
        add(new CaseEventPayload.MessageRedacted("m", "PERSONAL_DATA"), "m", ActorTrust.SYSTEM, null);

        MessageThread.Message message = MessageThread.of(this.events).getFirst();

        assertTrue(message.redacted());
        assertNull(message.text());
        assertEquals(MessageAuthorKind.PLAYER, message.authorKind());
    }

    @Test
    void messagesComeInTheOrderTheyWereAddedAndOtherEventsAreIgnored() {
        add(new CaseEventPayload.MessageAdded("a", null, MessageAuthorKind.STAFF, null, MessageChannel.PANEL, "first"), "a", ActorTrust.VERIFIED, null);
        add(new CaseEventPayload.NoteAdded("n", "note"), "n", ActorTrust.VERIFIED, null);
        add(new CaseEventPayload.MessageAdded("b", null, MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, "second"), "b", ActorTrust.PLAYER, null);
        add(new CaseEventPayload.MessageEdited("orphan", "no original"), "orphan", ActorTrust.VERIFIED, null);

        assertEquals(List.of("a", "b"), MessageThread.of(this.events).stream().map(MessageThread.Message::messageId).toList());
    }
}
