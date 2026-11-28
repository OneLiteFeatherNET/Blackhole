package net.onelitefeather.blackhole.backend.publicbans;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBanCursorTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final PublicBanCursor cursors = new PublicBanCursor(new PublicBanHasher(KEY));

    @Test
    void aCursorReadsBackTheDayAndTheHashItWasMadeFrom() {
        String cursor = this.cursors.encode(20_000L, "abcdefghijklmnopqrstuv", "NETWORK_BAN||");

        Optional<PublicBanCursor.Position> position = this.cursors.decode(cursor);

        assertTrue(position.isPresent(), "a cursor the backend signed is accepted");
        assertEquals(20_000L, position.get().day(), "the day of the last entry of the page");
        assertEquals("abcdefghijklmnopqrstuv", position.get().hash(), "the hash of the last entry of the page");
        assertEquals("NETWORK_BAN||", position.get().filters(), "the filters the page was issued under are in the payload");
    }

    @Test
    void aChangedCharacterInThePayloadIsRefused() {
        String cursor = this.cursors.encode(20_000L, "abcdefghijklmnopqrstuv", "NETWORK_BAN||");
        char first = cursor.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + cursor.substring(1);

        assertFalse(this.cursors.decode(tampered).isPresent(), "a tampered payload fails the signature");
    }

    @Test
    void aCursorSignedWithAnotherKeyIsRefused() {
        String foreign = new PublicBanCursor(new PublicBanHasher(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32})))
                .encode(20_000L, "abcdefghijklmnopqrstuv", "NETWORK_BAN||");

        assertFalse(this.cursors.decode(foreign).isPresent(), "a cursor from another deployment key is not ours");
    }

    @Test
    void garbageIsRefusedWithoutAnException() {
        assertFalse(this.cursors.decode("not-a-cursor").isPresent(), "a value without a signature is refused");
    }
}
