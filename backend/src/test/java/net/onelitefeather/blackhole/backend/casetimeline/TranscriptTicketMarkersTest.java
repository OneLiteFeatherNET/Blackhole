package net.onelitefeather.blackhole.backend.casetimeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The admin export carries the Discord ticket markers, so a reader can see that and when a ticket was opened, completed, archived and deleted. */
class TranscriptTicketMarkersTest {

    @Test
    void everyTicketMarkerIsPartOfTheExport() {
        for (String type : new String[]{"ticket.opened", "ticket.transcript_complete", "ticket.archived", "ticket.channel_deleted"}) {
            assertTrue(TranscriptRenderer.included(type, false), type + " is exported");
        }
    }

    @Test
    void theProtocolBookkeepingIsStillNotExported() {
        assertFalse(TranscriptRenderer.included("protocol.stored", false));
    }
}
