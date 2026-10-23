package net.onelitefeather.blackhole.backend.banid;

import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BanIdCryptoTest {

    private static final byte[] SECRET = TestKeys.randomBytes();
    private static final byte[] OTHER_SECRET = TestKeys.randomBytes();

    private final BanIdCrypto crypto = new BanIdCrypto(SECRET);

    @Test
    void theHashIsStableForOneKeyAndDiffersBetweenBanIdsAndKeys() {
        assertArrayEquals(this.crypto.hash("K7M29XYZ"), new BanIdCrypto(SECRET).hash("K7M29XYZ"), "same key, same hash");
        assertFalse(Arrays.equals(this.crypto.hash("K7M29XYZ"), this.crypto.hash("K7M29XY0")), "another Ban ID");
        assertFalse(Arrays.equals(this.crypto.hash("K7M29XYZ"), new BanIdCrypto(OTHER_SECRET).hash("K7M29XYZ")), "another key");
        assertEquals(32, this.crypto.hash("K7M29XYZ").length, "HMAC-SHA256");
    }

    @Test
    void encryptionRoundTripsWithTheSamePunishmentAsAssociatedData() {
        byte[] blob = this.crypto.encrypt("K7M29XYZ", "punishment-1");

        assertEquals("K7M29XYZ", this.crypto.decrypt(blob, "punishment-1"));
    }

    @Test
    void theCiphertextContainsNoPlainBanIdAndUsesAFreshNonce() {
        byte[] first = this.crypto.encrypt("K7M29XYZ", "punishment-1");
        byte[] second = this.crypto.encrypt("K7M29XYZ", "punishment-1");

        assertNotEquals(Arrays.toString(first), Arrays.toString(second), "a fresh nonce per encryption");
        assertFalse(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains("K7M29XYZ"), "no plain text");
    }

    @Test
    void aCiphertextMovedToAnotherPunishmentDoesNotDecrypt() {
        byte[] blob = this.crypto.encrypt("K7M29XYZ", "punishment-1");

        assertNull(this.crypto.decrypt(blob, "punishment-2"));
    }

    @Test
    void aCiphertextDoesNotDecryptUnderAnotherKey() {
        byte[] blob = this.crypto.encrypt("K7M29XYZ", "punishment-1");

        assertNull(new BanIdCrypto(OTHER_SECRET).decrypt(blob, "punishment-1"));
    }

    @Test
    void aTamperedOrTruncatedCiphertextDoesNotDecrypt() {
        byte[] blob = this.crypto.encrypt("K7M29XYZ", "punishment-1");
        blob[blob.length - 1] ^= 1;

        assertNull(this.crypto.decrypt(blob, "punishment-1"), "flipped bit");
        assertNull(this.crypto.decrypt(new byte[5], "punishment-1"), "shorter than a nonce");
        assertNull(this.crypto.decrypt(null, "punishment-1"), "nothing");
    }

    @Test
    void theKeyMustBeBase64OfThirtyTwoBytes() {
        assertThrows(IllegalStateException.class, () -> BanIdCrypto.decode("not base64!"));
        assertThrows(IllegalStateException.class, () -> BanIdCrypto.decode("c2hvcnQ="));
        assertThrows(IllegalStateException.class, () -> BanIdCrypto.decode(""));
        assertEquals(32, BanIdCrypto.decode(TestKeys.randomBase64()).length);
    }
}
