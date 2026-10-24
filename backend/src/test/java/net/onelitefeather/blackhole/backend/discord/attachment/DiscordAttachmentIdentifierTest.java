package net.onelitefeather.blackhole.backend.discord.attachment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The evidence id of a Discord file is derived, so a replay finds the same file and the Discord id is not stored. */
class DiscordAttachmentIdentifierTest {

    private static final UUID TICKET = UUID.fromString("0b6f7c2e-1d3a-4b5c-8e9f-112233445566");
    private static final UUID OTHER_TICKET = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-ffeeddccbbaa");

    @Test
    void sameTicketAndDiscordAttachmentGiveTheSameIdentifier() {
        assertEquals(DiscordAttachmentService.identifier(TICKET, "1234567890123"),
                DiscordAttachmentService.identifier(TICKET, "1234567890123"), "a replay must name the same evidence item");
    }

    @Test
    void differentDiscordAttachmentsGiveDifferentIdentifiers() {
        assertNotEquals(DiscordAttachmentService.identifier(TICKET, "1234567890123"),
                DiscordAttachmentService.identifier(TICKET, "1234567890124"), "two files must never share one evidence item");
    }

    @Test
    void theIdentifierDoesNotContainTheDiscordId() {
        String identifier = DiscordAttachmentService.identifier(TICKET, "1234567890123").toString();

        assertFalse(identifier.contains("1234567890123"), "the Discord attachment id must not appear in the stored id");
    }

    @Test
    void anotherTicketGivesAnotherIdentifierForTheSameDiscordAttachment() {
        assertNotEquals(DiscordAttachmentService.identifier(TICKET, "1234567890123"),
                DiscordAttachmentService.identifier(OTHER_TICKET, "1234567890123"), "files are scoped to their ticket");
    }
}
