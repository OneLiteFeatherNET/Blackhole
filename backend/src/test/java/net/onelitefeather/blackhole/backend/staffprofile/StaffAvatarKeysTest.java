package net.onelitefeather.blackhole.backend.staffprofile;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The avatar key is a keyed hash: stable per account, and free of the UUID text. */
class StaffAvatarKeysTest {

    private static final UUID FIRST = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID SECOND = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final StaffAvatarKeys keys = new StaffAvatarKeys("test-secret-for-unit-tests");

    @Test
    void theKeyIsStableForTheSameAccountAndSecret() {
        assertEquals(this.keys.keyFor(FIRST), new StaffAvatarKeys("test-secret-for-unit-tests").keyFor(FIRST));
    }

    @Test
    void theKeyDiffersBetweenAccounts() {
        assertNotEquals(this.keys.keyFor(FIRST), this.keys.keyFor(SECOND));
    }

    @Test
    void theKeyDiffersBetweenSecrets() {
        assertNotEquals(this.keys.keyFor(FIRST), new StaffAvatarKeys("another-secret").keyFor(FIRST));
    }

    @Test
    void theKeyHasTwentyTwoCharacters() {
        assertEquals(22, this.keys.keyFor(FIRST).length());
    }

    @Test
    void theKeyContainsNeitherTheUuidNorItsHexDigits() {
        String key = this.keys.keyFor(FIRST);
        assertFalse(key.contains(FIRST.toString()));
        assertFalse(key.contains(FIRST.toString().replace("-", "")));
    }
}
