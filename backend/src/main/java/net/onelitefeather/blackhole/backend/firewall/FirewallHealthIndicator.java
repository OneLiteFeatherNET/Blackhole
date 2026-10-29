package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.time.Clock;
import java.util.Map;

/**
 * The {@code ipban-firewall} health check. It is DOWN when no reconciliation has succeeded for three reconcile
 * intervals (counted from start-up until the first success), and UP while the layer is off. It reports; it never stops
 * the application or the proxy layer.
 */
@Singleton
public class FirewallHealthIndicator implements HealthIndicator {

    static final String NAME = "ipban-firewall";

    private final AliasReconciler reconciler;
    private final Clock clock;

    public FirewallHealthIndicator(AliasReconciler reconciler, Clock clock) {
        this.reconciler = reconciler;
        this.clock = clock;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        return Publishers.just(result(this.clock.millis()));
    }

    /** The check at {@code now}; package-private so the test can read it with a fixed clock. */
    HealthResult result(long now) {
        if (!this.reconciler.enabled()) {
            return HealthResult.builder(NAME, HealthStatus.UP).details(Map.of("layer", "disabled")).build();
        }
        long staleAfter = 3 * this.reconciler.interval().toMillis();
        boolean stale = now - this.reconciler.lastSuccessOrStart() > staleAfter;
        HealthStatus status = stale ? HealthStatus.DOWN : HealthStatus.UP;
        return HealthResult.builder(NAME, status).details(Map.of("layer", "enabled", "stale", stale)).build();
    }
}
