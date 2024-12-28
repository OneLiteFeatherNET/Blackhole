package net.onelitefeather.blackhole.api.utils;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UUIDConverterTest {

    @Test
    void testUUIDConversation() {
        UUID randomUUID = UUID.randomUUID();
        String uuidHash = UUIDConverter.convertToSHA(randomUUID);
        assertNotNull(uuidHash);
        assertNotEquals(String.valueOf(randomUUID.hashCode()), uuidHash);
    }

    @Test
    void testHashEquality() {
        UUID randomUUID = UUID.randomUUID();
        String uuidHash = UUIDConverter.convertToSHA(randomUUID);
        assertNotNull(uuidHash);
        assertEquals(uuidHash, UUIDConverter.convertToSHA(randomUUID));
    }

    @Test
    void testUniqueGeneration() {
        UUID randomUUID = UUID.randomUUID();
        String uuidHash = UUIDConverter.convertToSHA(randomUUID);
        assertNotNull(uuidHash);
        assertNotEquals(uuidHash, UUIDConverter.convertToSHA(UUID.randomUUID()));
    }
}