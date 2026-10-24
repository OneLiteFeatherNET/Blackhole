package net.onelitefeather.blackhole.backend.evasion;

import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientFingerprintHasherTest {

    /** A fixed, public test vector key (bytes 0 to 31): never a real secret. */
    private static byte[] vectorKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    private static ClientFingerprintTraits traits(String brand, List<String> channels, List<String> mods) {
        return ClientFingerprintTraits.of(brand, channels, mods).orElseThrow();
    }

    @Test
    void theFingerprintMatchesTheKnownAnswers() {
        ClientFingerprintHasher hasher = new ClientFingerprintHasher(vectorKey());

        assertEquals("a16911badc72feed874086a379c7946f8d08392adaa689886ec2e9af09a8967f",
                hasher.fingerprint(traits("fabric", List.of("meteor-client:main", "fabric:registry/sync"), List.of()).canonical()));
        assertEquals("6e5f76b700b6ce01a055d8f8e7a6e53dd168302f8f30082628408fa152cbb577",
                hasher.fingerprint(traits("LunarClient:abc", List.of(), List.of("jei@4.16.1")).canonical()));
        assertEquals("c28200b0f656d40f0f7dd0e2a733aa13078a3b97bf9d79740060b428548a842e", hasher.fingerprint(traits(null, null, null).canonical()));
    }

    @Test
    void theFingerprintIsSixtyFourLowerCaseHexCharacters() {
        String fingerprint = new ClientFingerprintHasher(TestKeys.randomBytes()).fingerprint("v1\nbrand:0:\nchannels:0:\nmods:0:");

        assertTrue(fingerprint.matches("[0-9a-f]{64}"), fingerprint);
    }

    @Test
    void anotherKeyGivesAnotherFingerprint() {
        String canonical = traits("x", List.of("a:b"), List.of()).canonical();

        assertNotEquals(new ClientFingerprintHasher(TestKeys.randomBytes()).fingerprint(canonical),
                new ClientFingerprintHasher(TestKeys.randomBytes()).fingerprint(canonical));
    }

    @Test
    void aHasherWithoutAKeyIsOffAndRefusesToHash() {
        ClientFingerprintHasher off = new ClientFingerprintHasher((byte[]) null);

        assertFalse(off.isEnabled());
        assertThrows(IllegalStateException.class, () -> off.fingerprint("v1"));
    }

    @Test
    void anUnsetKeyIsOffOutsideDevAndTestAndRandomInsideThem() {
        assertFalse(new ClientFingerprintHasher("", Set.of("k8s")).isEnabled(), "off in production");
        assertFalse(new ClientFingerprintHasher(null, Set.of()).isEnabled(), "off without any environment");
        ClientFingerprintHasher dev = new ClientFingerprintHasher("", Set.of("dev"));
        ClientFingerprintHasher other = new ClientFingerprintHasher("", Set.of("test"));
        assertTrue(dev.isEnabled(), "a random key in dev");
        assertNotEquals(dev.fingerprint("v1"), other.fingerprint("v1"), "a fresh random key per run");
    }

    @Test
    void aConfiguredKeyWorksEverywhere() {
        assertTrue(new ClientFingerprintHasher(TestKeys.randomBase64(), Set.of("k8s")).isEnabled());
    }

    @Test
    void theGuardRefusesAMalformedKeyWithoutNamingItAndAcceptsUnsetAndValidKeys() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> ClientFingerprintKeyGuard.check("short-key"));

        assertFalse(refused.getMessage().contains("short-key"), "the key is not echoed");
        assertDoesNotThrow(() -> ClientFingerprintKeyGuard.check(""));
        assertDoesNotThrow(() -> ClientFingerprintKeyGuard.check(null));
        assertDoesNotThrow(() -> ClientFingerprintKeyGuard.check(TestKeys.randomBase64()));
    }

    @Test
    void thePropertiesNeverPrintTheKey() {
        assertFalse(new ClientFingerprintProperties("abcdef").toString().contains("abcdef"));
    }
}
