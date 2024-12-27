package net.onelitefeather.blackhole.api.punish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PunishEntryTest {

    @Test
    void testObjectCreation() {
        long current = System.currentTimeMillis();
        PunishEntry entry = PunishEntry.builder()
                .type(PunishType.SERVER)
                .build();
        assertNotNull(entry);
        assertEquals(PunishType.SERVER, entry.type());
        assertEquals(current, entry.creationDate());
    }

    @Test
    void testObjectManipulation() {
        PunishEntry entry = PunishEntry.builder()
                .type(PunishType.SERVER)
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
