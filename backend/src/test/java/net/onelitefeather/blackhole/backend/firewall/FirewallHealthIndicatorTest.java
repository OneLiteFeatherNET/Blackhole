package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.health.HealthStatus;
import net.onelitefeather.blackhole.backend.ipban.IpBanFixture;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FirewallHealthIndicatorTest {

    private static final long NOW = IpBanFixture.NOW;

    private static AliasReconciler reconciler(AliasReconcilerTest.MovableClock clock, boolean enabled) {
        return new AliasReconciler(new IpBanFixture().store, new IpBanProtection(""), new AliasReconcilerTest.FakeGateway(),
                clock, new SimpleMeterRegistry(), enabled, "blackhole_ipban", Duration.ofMinutes(5));
    }

    @Test
    void aLayerThatNeverSucceededIsDownAfterThreeIntervals() {
        AliasReconcilerTest.MovableClock clock = new AliasReconcilerTest.MovableClock();
        FirewallHealthIndicator indicator = new FirewallHealthIndicator(reconciler(clock, true), clock);

        assertEquals(HealthStatus.UP, indicator.result(NOW + Duration.ofMinutes(14).toMillis()).getStatus(),
                "within three intervals of start-up the check is still UP");
        assertEquals(HealthStatus.DOWN, indicator.result(NOW + Duration.ofMinutes(16).toMillis()).getStatus(),
                "after three intervals without a success it is DOWN");
    }

    @Test
    void aDisabledLayerIsUp() {
        AliasReconcilerTest.MovableClock clock = new AliasReconcilerTest.MovableClock();
        FirewallHealthIndicator indicator = new FirewallHealthIndicator(reconciler(clock, false), clock);

        assertEquals(HealthStatus.UP, indicator.result(NOW + Duration.ofDays(1).toMillis()).getStatus(),
                "the layer being off is not a failure");
    }
}
