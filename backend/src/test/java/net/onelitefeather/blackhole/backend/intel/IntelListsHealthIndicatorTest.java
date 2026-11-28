package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.support.InMemoryIntelRecordStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelListsHealthIndicatorTest {

    private static final Duration INTERVAL = Duration.ofHours(6);

    private final TestClock clock = new TestClock();
    private final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();

    private static IntelListSourceConfig source(String key, boolean enabled) {
        IntelListSourceConfig config = new IntelListSourceConfig(key);
        config.setEnabled(enabled);
        return config;
    }

    /** A source whose last success was {@code successAgo} ago and whose latest refresh failed. */
    private void failingSince(String key, Duration successAgo) {
        long success = this.clock.millis() - successAgo.toMillis();
        this.store.putSourceStatus(new IntelSourceStatus(key, this.clock.millis(), success, "the refresh timed out", 100, 0));
    }

    private void healthySource(String key) {
        long now = this.clock.millis();
        this.store.putSourceStatus(new IntelSourceStatus(key, now, now, null, 100, 0));
    }

    private IntelListsHealthIndicator indicator(IntelListSourceConfig... sources) {
        return new IntelListsHealthIndicator(this.store, this.clock, List.of(sources), INTERVAL);
    }

    @Test
    void oneSourceFailingWhileAnotherIsHealthyStaysUp() {
        failingSince("x4bnet-vpn", Duration.ofDays(3));
        healthySource("x4bnet-datacenter");

        assertFalse(indicator(source("x4bnet-vpn", true), source("x4bnet-datacenter", true)).isDown(),
                "one healthy source keeps the indicator UP");
    }

    @Test
    void everyEnabledSourceFailingForMoreThanTwoIntervalsIsDown() {
        failingSince("x4bnet-vpn", Duration.ofHours(13));
        failingSince("x4bnet-datacenter", Duration.ofHours(13));

        assertTrue(indicator(source("x4bnet-vpn", true), source("x4bnet-datacenter", true)).isDown(),
                "both sources failed for longer than two 6-hour intervals");
    }

    @Test
    void everyEnabledSourceFailingForLessThanTwoIntervalsIsStillUp() {
        failingSince("x4bnet-vpn", Duration.ofHours(4));
        failingSince("x4bnet-datacenter", Duration.ofHours(4));

        assertFalse(indicator(source("x4bnet-vpn", true), source("x4bnet-datacenter", true)).isDown(),
                "a recent failure is inside the two-interval allowance");
    }

    /** A source that has never succeeded and whose latest refresh failed. */
    private void neverSucceeded(String key) {
        this.store.putSourceStatus(new IntelSourceStatus(key, this.clock.millis(), null, "the refresh timed out", 0, 0));
    }

    @Test
    void aSourceThatNeverSucceededAndStartedThreeIntervalsAgoIsDown() {
        IntelListsHealthIndicator indicator = indicator(source("x4bnet-vpn", true));
        this.clock.advance(INTERVAL.multipliedBy(3));
        neverSucceeded("x4bnet-vpn");

        assertTrue(indicator.isDown(), "a source that never loaded, three intervals after start-up, is DOWN");
    }

    @Test
    void aSourceThatNeverSucceededAndStartedOneIntervalAgoIsUp() {
        IntelListsHealthIndicator indicator = indicator(source("x4bnet-vpn", true));
        this.clock.advance(INTERVAL);
        neverSucceeded("x4bnet-vpn");

        assertFalse(indicator.isDown(), "one interval after start-up is still inside the allowance");
    }

    @Test
    void aDisabledSourceDoesNotCountTowardsDown() {
        failingSince("x4bnet-vpn", Duration.ofHours(13));

        assertTrue(indicator(source("x4bnet-vpn", true), source("x4bnet-datacenter", false)).isDown(),
                "the disabled source is ignored, so the only enabled source decides");
    }
}
