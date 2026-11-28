package net.onelitefeather.blackhole.backend.publicbans;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBanHasherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    @Test
    void entryHashIsTwentyTwoBase64UrlCharacters() {
        String hash = new PublicBanHasher(KEY).entryHash("punishment-1");
        assertEquals(22, hash.length(), "the entry hash must be 128 bits in base64url without padding");
        assertTrue(hash.matches("[A-Za-z0-9_-]{22}"), "the entry hash must use the base64url alphabet only: " + hash);
    }

    @Test
    void sameIdentifierAndKeyGiveTheSameEntryHash() {
        assertEquals(new PublicBanHasher(KEY).entryHash("punishment-1"), new PublicBanHasher(KEY).entryHash("punishment-1"),
                "the entry hash must be stable across instances with the same key");
    }

    @Test
    void differentIdentifiersGiveDifferentEntryHashes() {
        PublicBanHasher hasher = new PublicBanHasher(KEY);
        assertFalse(hasher.entryHash("punishment-1").equals(hasher.entryHash("punishment-2")),
                "two identifiers must not share an entry hash");
    }

    @Test
    void differentKeysGiveDifferentEntryHashes() {
        assertFalse(new PublicBanHasher(KEY).entryHash("punishment-1").equals(new PublicBanHasher(OTHER_KEY).entryHash("punishment-1")),
                "a different BLACKHOLE_PUBLIC_BAN_KEY must change the entry hash");
    }

    @Test
    void entryHashAndCursorSignatureUseSeparateLabels() {
        PublicBanHasher hasher = new PublicBanHasher(KEY);
        assertFalse(hasher.entryHash("x").equals(hasher.signCursor("x")),
                "the entry hash and the cursor signature must not share a key or a message");
    }
}
