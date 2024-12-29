package net.onelitefeather.blackhole.api.punish;

import net.onelitefeather.blackhole.api.template.PunishTemplate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

class PunishEntryTest {

    @Test
    void testObjectCreation() {
        long current = System.currentTimeMillis();
        PunishTemplate template = PunishTemplate.builder()
                .reason("Test")
                .type(PunishType.NETWORK)
                .build();
        PunishEntry entry = PunishEntry.builder()
                .type(PunishType.SERVER)
                .source(UUID.randomUUID())
                .template(template)
                .build();
        assertNotNull(entry);
        assertEquals(PunishType.SERVER, entry.type());
        assertEquals(current, entry.creationDate());
    }

    @Test
    void testObjectManipulation() {
        PunishTemplate template = PunishTemplate.builder()
                .reason("Test")
                .type(PunishType.NETWORK)
                .build();

        PunishEntry entry = PunishEntry.builder()
                .type(PunishType.SERVER)
                .source(UUID.randomUUID())
                .template(template)
                .build();
        assertNotNull(entry);

        PunishEntry updated = PunishEntry.builder(entry)
                .type(PunishType.CHAT)
                .build();
        assertNotNull(updated);

        assertEquals(PunishType.CHAT, updated.type());
        assertNotEquals(entry, updated);
        assertNotEquals(entry.type(), updated.type());
    }

    @Test
    void testMissingTypeUsage() {
        assertThrowsExactly(
                IllegalStateException.class,
                () -> PunishEntry.builder().build(),
                "The ban type must be set"
        );
    }
}
