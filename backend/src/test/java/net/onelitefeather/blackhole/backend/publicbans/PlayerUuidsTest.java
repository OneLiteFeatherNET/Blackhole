package net.onelitefeather.blackhole.backend.publicbans;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerUuidsTest {

    private static final UUID VECTOR = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void dashedLowerCaseIsAccepted() {
        assertEquals(VECTOR, PlayerUuids.parse("123e4567-e89b-12d3-a456-426614174000").orElseThrow());
    }

    @Test
    void dashedUpperCaseIsStoredLowerCase() {
        assertEquals(VECTOR, PlayerUuids.parse("123E4567-E89B-12D3-A456-426614174000").orElseThrow(),
                "the same UUID in any case is one UUID; it is stored in lowercase dashed form");
    }

    @Test
    void thirtyTwoHexDigitsAreAccepted() {
        assertEquals(VECTOR, PlayerUuids.parse("123e4567e89b12d3a456426614174000").orElseThrow(),
                "the undashed 32-digit form is the same UUID");
    }

    @Test
    void anyOtherValueIsRefused() {
        assertFalse(PlayerUuids.parse("not-a-uuid").isPresent(), "a value that is no UUID is refused");
        assertFalse(PlayerUuids.parse("123e4567-e89b-12d3-a456-42661417400").isPresent(), "a short value is refused");
        assertFalse(PlayerUuids.parse("").isPresent(), "an empty value is refused");
    }

    @Test
    void absentValueIsNotAUuid() {
        assertTrue(PlayerUuids.parse(null).isEmpty(), "an absent value yields no UUID and is not an error by itself");
    }
}
