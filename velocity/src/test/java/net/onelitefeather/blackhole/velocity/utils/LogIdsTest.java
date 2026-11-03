package net.onelitefeather.blackhole.velocity.utils;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogIdsTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000042");

    @Test
    void aLogIdIsTheStartOfTheOwnerHash() {
        String hash = UUIDConverter.convertToSHA(PLAYER);

        assertEquals(hash.substring(0, 8), LogIds.of(PLAYER), "the same id for the same player, in every log line");
        assertEquals(hash.substring(0, 8), LogIds.of(hash));
    }

    @Test
    void aShortHashIsLoggedWhole() {
        assertEquals("abc", LogIds.of("abc"));
    }
}
