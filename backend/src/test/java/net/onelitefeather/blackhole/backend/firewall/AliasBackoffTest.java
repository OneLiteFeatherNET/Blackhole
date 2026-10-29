package net.onelitefeather.blackhole.backend.firewall;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasBackoffTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void afterOneFailureTheRunWaitsThirtySeconds() {
        AliasBackoff backoff = new AliasBackoff();

        backoff.failed(NOW);

        assertTrue(backoff.waiting(NOW + Duration.ofSeconds(29).toMillis()), "still waiting before 30 seconds");
        assertFalse(backoff.waiting(NOW + Duration.ofSeconds(30).toMillis()), "free again after 30 seconds");
    }

    @Test
    void eachFurtherFailureDoublesTheWait() {
        AliasBackoff backoff = new AliasBackoff();
        backoff.failed(NOW);
        backoff.failed(NOW);

        assertTrue(backoff.waiting(NOW + Duration.ofSeconds(59).toMillis()), "the second failure waits 60 seconds");
        assertFalse(backoff.waiting(NOW + Duration.ofSeconds(60).toMillis()), "and not longer");
    }

    @Test
    void theWaitIsCappedAtFifteenMinutes() {
        AliasBackoff backoff = new AliasBackoff();
        for (int i = 0; i < 40; i++) {
            backoff.failed(NOW);
        }

        assertFalse(backoff.waiting(NOW + Duration.ofMinutes(15).toMillis()), "the cap is fifteen minutes");
    }

    @Test
    void aSuccessResetsTheBackoff() {
        AliasBackoff backoff = new AliasBackoff();
        backoff.failed(NOW);
        backoff.failed(NOW);

        backoff.succeeded();

        assertEquals(0, backoff.failures(), "the failure count is reset");
        assertFalse(backoff.waiting(NOW), "the next run is not delayed");
    }
}
