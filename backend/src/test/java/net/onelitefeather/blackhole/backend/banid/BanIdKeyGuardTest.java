package net.onelitefeather.blackhole.backend.banid;

import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BanIdKeyGuardTest {

    @Test
    void anUnsetKeyIsRefusedOutsideDevAndTest() {
        assertThrows(IllegalStateException.class, () -> BanIdKeyGuard.check("", Set.of("k8s")));
        assertThrows(IllegalStateException.class, () -> BanIdKeyGuard.check(null, Set.of("k8s")));
    }

    @Test
    void anUnsetKeyIsAllowedInDevAndTest() {
        assertDoesNotThrow(() -> BanIdKeyGuard.check("", Set.of("dev")));
        assertDoesNotThrow(() -> BanIdKeyGuard.check("", Set.of("test")));
    }

    @Test
    void aRealKeyIsAcceptedEverywhere() {
        assertDoesNotThrow(() -> BanIdKeyGuard.check(TestKeys.randomBase64(), Set.of("k8s")));
    }

    @Test
    void aMalformedKeyIsRefusedEvenInDevWithoutNamingIt() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> BanIdKeyGuard.check("short-key", Set.of("dev")));

        assertFalse(refused.getMessage().contains("short-key"), "the key is not echoed");
    }

    @Test
    void anUnsetKeyGivesAWorkingRandomKeyThatDiffersPerRun() {
        BanIdCrypto first = new BanIdCrypto(new BanIdProperties(""));
        BanIdCrypto second = new BanIdCrypto(new BanIdProperties(""));

        assertFalse(Arrays.equals(first.hash("K7M29XYZ"), second.hash("K7M29XYZ")), "a fresh key per run");
    }
}
