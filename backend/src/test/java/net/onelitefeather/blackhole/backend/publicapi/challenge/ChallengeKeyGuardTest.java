package net.onelitefeather.blackhole.backend.publicapi.challenge;

import net.onelitefeather.blackhole.backend.support.TestKeys;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The key, the switch and the tunables, checked the same way as the Ban ID key. Static checks only; no context starts. */
class ChallengeKeyGuardTest {

    private static final Set<String> PRODUCTION = Set.of("k8s");
    private static final Set<String> DEV = Set.of("dev");
    private static final Set<String> TEST = Set.of("test");

    @Test
    void anUnsetKeyIsRefusedOutsideLocalEnvironments() {
        assertThrows(IllegalStateException.class, () -> ChallengeKeyGuard.requireKey("", PRODUCTION), "a deployment needs its own key");
    }

    @Test
    void aMissingKeyIsRefusedOutsideLocalEnvironmentsToo() {
        assertThrows(IllegalStateException.class, () -> ChallengeKeyGuard.requireKey(null, PRODUCTION), "a null key is refused like an empty one");
    }

    @Test
    void anUnsetKeyIsAllowedInDev() {
        assertDoesNotThrow(() -> ChallengeKeyGuard.requireKey("", DEV), "dev runs without a key");
    }

    @Test
    void anUnsetKeyIsAllowedInTest() {
        assertDoesNotThrow(() -> ChallengeKeyGuard.requireKey("", TEST), "tests run without a key");
    }

    @Test
    void aKeyOfThirtyTwoBytesIsAcceptedInProduction() {
        assertDoesNotThrow(() -> ChallengeKeyGuard.requireKey(TestKeys.randomBase64(), PRODUCTION), "a real key is accepted in production");
    }

    @Test
    void aShortKeyIsRefusedOutsideLocalEnvironments() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

        assertThrows(IllegalStateException.class, () -> ChallengeKeyGuard.requireKey(sixteenBytes, PRODUCTION), "a key under 32 bytes is refused");
    }

    @Test
    void aMalformedKeyIsRefusedWithoutEchoingIt() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ChallengeKeyGuard.requireKey("not-base64-!!", DEV), "a key that is not base64 is refused even in dev");

        assertFalse(refused.getMessage().contains("not-base64"), "the key is never named in the message");
    }

    @Test
    void aDisabledSwitchIsRefusedOutsideLocalEnvironments() {
        assertThrows(IllegalStateException.class, () -> ChallengeKeyGuard.requireSwitch(false, PRODUCTION),
                "the check cannot be switched off in a deployment");
    }

    @Test
    void aDisabledSwitchIsAllowedInTest() {
        assertDoesNotThrow(() -> ChallengeKeyGuard.requireSwitch(false, TEST), "tests may switch the check off");
    }

    @Test
    void anEnabledSwitchIsAllowedInProduction() {
        assertDoesNotThrow(() -> ChallengeKeyGuard.requireSwitch(true, PRODUCTION), "the check is on by default");
    }

    @Test
    void anOddLengthPrefixIsRefused() {
        assertThrows(IllegalStateException.class, () -> ChallengeKeyGuard.requirePrefix("0"), "a prefix is whole hex bytes");
    }

    @Test
    void aPrefixThatIsNotHexIsRefused() {
        assertThrows(IllegalStateException.class, () -> ChallengeKeyGuard.requirePrefix("zz"), "a prefix must be hexadecimal");
    }

    @Test
    void theDefaultPrefixIsAccepted() {
        assertDoesNotThrow(() -> ChallengeKeyGuard.requirePrefix("00"), "the default prefix is accepted");
    }

    @Test
    void aLongerEvenPrefixIsAccepted() {
        assertDoesNotThrow(() -> ChallengeKeyGuard.requirePrefix("ff00"), "a longer even prefix is accepted");
    }

    @Test
    void aZeroCostIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> ChallengeKeyGuard.requireTunables(0, Duration.ofMinutes(5), Duration.ofMillis(500)), "the cost must be positive");
    }

    @Test
    void aZeroExpiryIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> ChallengeKeyGuard.requireTunables(5000, Duration.ZERO, Duration.ofMillis(500)), "a challenge must expire");
    }

    @Test
    void aLocalRunWithoutAKeyGetsA32ByteKey() {
        String generated = ChallengeKeyGuard.resolveKey("", DEV);

        assertEquals(32, Base64.getDecoder().decode(generated).length, "the generated key is 32 bytes");
    }

    @Test
    void aLocalRunWithoutAKeyGetsAFreshKeyEachTime() {
        String first = ChallengeKeyGuard.resolveKey("", DEV);
        String second = ChallengeKeyGuard.resolveKey("", DEV);

        assertFalse(first.equals(second), "each run gets its own key");
    }
}
