package net.onelitefeather.blackhole.backend.ipban;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpBanActiveRuleTest {

    private static final long NOW = 1_000_000L;

    private static IpBanRecord ban(Long expiresAt, Long liftedAt) {
        return new IpBanRecord(UUID.randomUUID(), IpBanNetwork.parse("203.0.113.7").orElseThrow().network(), "Bot wave",
                null, IpBanSource.PANEL, null, null, UUID.randomUUID(), NOW - 10, expiresAt, liftedAt, null, null);
    }

    @Test
    void aBanWhoseExpiryIsNowIsInactive() {
        assertFalse(ban(NOW, null).isActive(NOW), "expiry equal to now means expired");
    }

    @Test
    void aLiftedBanIsInactive() {
        assertFalse(ban(null, NOW - 1).isActive(NOW), "a lifted ban no longer holds");
    }

    @Test
    void aBanWithoutExpiryIsActive() {
        assertTrue(ban(null, null).isActive(NOW), "a permanent ban holds");
    }

    @Test
    void aBanThatExpiresLaterIsActive() {
        assertTrue(ban(NOW + 1, null).isActive(NOW), "an expiry after now still holds");
    }
}
