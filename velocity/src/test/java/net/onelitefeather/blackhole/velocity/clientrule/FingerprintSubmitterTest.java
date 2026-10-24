package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintSubmitterTest {

    private static final String OWNER = "ab".repeat(64);
    private static final Duration SETTLE = Duration.ofSeconds(5);

    private final TraitCollector traits = new TraitCollector();
    private final FakeClientRuleBackend backend = new FakeClientRuleBackend();
    private final ManualTimer timer = new ManualTimer();
    private final MutableClock clock = new MutableClock();
    private final FingerprintSubmitter submitter = new FingerprintSubmitter(traits, backend, timer, clock, SETTLE, Runnable::run);

    private void cheatClient() {
        traits.loggedIn(OWNER, 767);
        traits.brand(OWNER, "fabric");
        traits.channels(OWNER, List.of("minecraft:brand", "meteor-client:main", "fabric:registry/sync"));
    }

    @Test
    void nothingIsSubmittedBeforeTheFirstConnectSettles() {
        cheatClient();

        timer.advance(Duration.ofMinutes(1));

        assertTrue(backend.fingerprints.isEmpty());
    }

    @Test
    void theTraitsAreSubmittedOnceTheSettlePeriodAfterTheFirstConnectIsOver() {
        cheatClient();
        submitter.connected(OWNER);

        timer.advance(SETTLE.minusSeconds(1));
        assertTrue(backend.fingerprints.isEmpty(), "still settling");
        timer.advance(Duration.ofSeconds(1));

        assertEquals(1, backend.fingerprints.size());
        assertEquals(OWNER, backend.fingerprints.getFirst().getOwner());
        assertEquals("fabric", backend.fingerprints.getFirst().getBrand());
        assertEquals(List.of("fabric:registry/sync", "meteor-client:main"), backend.fingerprints.getFirst().getChannels(),
                "sorted, without the minecraft namespace");
    }

    @Test
    void aTraitThatArrivesDuringTheSettlePeriodIsPartOfThatSubmission() {
        traits.loggedIn(OWNER, 767);
        submitter.connected(OWNER);
        timer.advance(Duration.ofSeconds(2));
        traits.brand(OWNER, "lunarclient");
        submitter.traitsChanged(OWNER);

        timer.advance(Duration.ofSeconds(10));

        assertEquals(1, backend.fingerprints.size(), "the change did not start a second submission");
        assertEquals("lunarclient", backend.fingerprints.getFirst().getBrand());
    }

    @Test
    void aSecondConnectWithUnchangedTraitsSubmitsNothingMore() {
        cheatClient();
        submitter.connected(OWNER);
        timer.advance(SETTLE);

        submitter.connected(OWNER);
        timer.advance(Duration.ofMinutes(1));

        assertEquals(1, backend.fingerprints.size());
    }

    @Test
    void aChangedTraitSetIsSubmittedAgainAfterAnotherSettlePeriod() {
        cheatClient();
        submitter.connected(OWNER);
        timer.advance(SETTLE);

        traits.channels(OWNER, List.of("wurst:main"));
        submitter.traitsChanged(OWNER);
        timer.advance(SETTLE);

        assertEquals(2, backend.fingerprints.size());
        assertTrue(backend.fingerprints.get(1).getChannels().contains("wurst:main"));
    }

    @Test
    void theProtocolVersionIsNotPartOfTheComparison() {
        cheatClient();
        submitter.connected(OWNER);
        timer.advance(SETTLE);

        traits.loggedIn(OWNER, 769);
        submitter.traitsChanged(OWNER);
        timer.advance(SETTLE);

        assertEquals(1, backend.fingerprints.size());
    }

    @Test
    void aPlayerWhoLeftBeforeTheSettlePeriodIsOverIsNotSubmitted() {
        cheatClient();
        submitter.connected(OWNER);

        traits.forget(OWNER);
        submitter.forget(OWNER);
        timer.advance(SETTLE);

        assertTrue(backend.fingerprints.isEmpty());
    }

    @Test
    void aBackendWithFingerprintingOffPausesSubmissionsForTenMinutes() {
        cheatClient();
        backend.fingerprintFailure = new ApiException(503, "off");
        submitter.connected(OWNER);
        timer.advance(SETTLE);
        backend.fingerprintFailure = null;

        traits.channels(OWNER, List.of("wurst:main"));
        submitter.traitsChanged(OWNER);
        timer.advance(SETTLE);
        assertTrue(backend.fingerprints.isEmpty(), "paused");

        clock.advance(FingerprintSubmitter.PAUSE_AFTER_UNAVAILABLE.plusSeconds(1));
        submitter.traitsChanged(OWNER);
        timer.advance(SETTLE);
        assertEquals(1, backend.fingerprints.size(), "the pause is over");
    }

    @Test
    void aFailedSubmissionIsRetriedOnTheNextTraitChange() {
        cheatClient();
        backend.fingerprintFailure = new ApiException(500, "boom");
        submitter.connected(OWNER);
        timer.advance(SETTLE);
        backend.fingerprintFailure = null;

        submitter.traitsChanged(OWNER);
        timer.advance(SETTLE);

        assertEquals(1, backend.fingerprints.size());
    }

    @Test
    void anOddClientCannotMakeTheSubmissionTooBigForTheBackend() {
        traits.loggedIn(OWNER, 767);
        traits.brand(OWNER, "b".repeat(100));
        traits.channels(OWNER, IntStream.range(0, 400).mapToObj(i -> String.format("c:%03d", i)).toList());
        submitter.connected(OWNER);

        timer.advance(SETTLE);

        assertEquals(1, backend.fingerprints.size());
        assertEquals(FingerprintSubmitter.MAX_ENTRIES, backend.fingerprints.getFirst().getChannels().size());
        assertEquals(FingerprintSubmitter.MAX_BRAND, backend.fingerprints.getFirst().getBrand().length());
    }
}
