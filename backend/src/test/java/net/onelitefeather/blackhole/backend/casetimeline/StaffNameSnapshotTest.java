package net.onelitefeather.blackhole.backend.casetimeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The display name snapshot of a staff message: payload version 2, read back, and never for other types. Pure. */
class StaffNameSnapshotTest {

    private static CaseEventPayload.MessageAdded staffMessage(String name) {
        return new CaseEventPayload.MessageAdded("m-1", null, MessageAuthorKind.STAFF, null, MessageChannel.PANEL, "hi",
                List.of(), List.of(), name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> author(CaseEventPayload payload) {
        return (Map<String, Object>) payload.toMap().get("author");
    }

    @Test
    void aNamedStaffMessageIsVersionTwoAndCarriesTheSnapshotInItsAuthor() {
        CaseEventPayload.MessageAdded payload = staffMessage("Mia K.");

        assertEquals(CaseEventPayload.NAMED_STAFF_MESSAGE_VERSION, payload.version());
        assertEquals("Mia K.", author(payload).get("staffDisplayName"));
    }

    @Test
    void aStaffMessageWithoutANameStaysVersionOneAndCarriesNoName() {
        CaseEventPayload.MessageAdded payload = staffMessage(null);

        assertEquals(CaseEventPayload.CURRENT_VERSION, payload.version(), "a legacy message keeps the old version");
        assertFalse(author(payload).containsKey("staffDisplayName"), "no name is invented");
    }

    @Test
    void aPlayerMessageNeverBecomesVersionTwo() {
        CaseEventPayload.MessageAdded payload = new CaseEventPayload.MessageAdded("m-2", null, MessageAuthorKind.PLAYER, null,
                MessageChannel.PORTAL, "hi", List.of(), List.of(), "Mia K.");

        assertEquals(CaseEventPayload.CURRENT_VERSION, payload.version());
    }

    @Test
    void aVersionTwoMessageIsReadBackWithItsSnapshotName() {
        Map<String, Object> body = staffMessage("Mia K.").toMap();

        CaseEventPayload read = CaseEventPayloads.read(CaseEventPayload.MESSAGE_ADDED, CaseEventPayload.NAMED_STAFF_MESSAGE_VERSION, body);

        assertEquals("Mia K.", assertInstanceOf(CaseEventPayload.MessageAdded.class, read).staffDisplayName());
    }

    @Test
    void aVersionTwoEventOfAnotherTypeIsStillUnknown() {
        Map<String, Object> body = staffMessage("Mia K.").toMap();

        CaseEventPayload read = CaseEventPayloads.read(CaseEventPayload.MESSAGE_EDITED, CaseEventPayload.NAMED_STAFF_MESSAGE_VERSION, body);

        assertInstanceOf(CaseEventPayload.Unknown.class, read, "only message.added has a version 2");
    }
}
