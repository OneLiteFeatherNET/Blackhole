package net.onelitefeather.blackhole.backend.analytics;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The schedule is declared, not started: the annotations carry the default interval and the switch. No clock, no sleep. */
class AnalyticsRefreshSchedulerTest {

    @Test
    void theDefaultIntervalIsFifteenMinutes() throws NoSuchMethodException {
        Scheduled schedule = AnalyticsRefreshScheduler.class.getDeclaredMethod("refresh").getAnnotation(Scheduled.class);

        assertEquals("${blackhole.analytics.refresh.interval:15m}", schedule.fixedDelay(),
                "the interval comes from configuration and defaults to 15 minutes");
    }

    @Test
    void theSchedulerExistsOnlyWhileTheRefreshIsEnabled() {
        Requires switchOn = AnalyticsRefreshScheduler.class.getAnnotation(Requires.class);

        assertEquals("blackhole.analytics.refresh.enabled", switchOn.property());
        assertEquals("true", switchOn.value());
        assertEquals("true", switchOn.defaultValue(), "enabled unless configured otherwise");
    }
}
