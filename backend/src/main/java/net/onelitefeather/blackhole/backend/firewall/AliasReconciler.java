package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import net.onelitefeather.blackhole.backend.ipban.IpBanChanges;
import net.onelitefeather.blackhole.backend.ipban.IpBanNetwork;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import net.onelitefeather.blackhole.backend.ipban.IpBanRecord;
import net.onelitefeather.blackhole.backend.ipban.IpBanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the firewall alias equal to the active IP bans. The database is the truth: each run reads the alias, writes
 * only the difference, reconfigures once when something changed, and reads the alias back; it succeeds only when the
 * read-back equals the desired set. Runs happen at start-up, every reconcile interval, and soon after a write
 * (debounced to one run per tick). Nothing runs while the firewall layer is off. Log lines carry no address.
 */
@Singleton
public class AliasReconciler implements IpBanChanges {

    /** How the last run ended, for the caller and the tests. */
    public enum Outcome { DISABLED, SKIPPED, IN_SYNC, FAILED }

    private static final Logger LOGGER = LoggerFactory.getLogger(AliasReconciler.class);
    private static final long DEBOUNCE_MILLIS = Duration.ofSeconds(5).toMillis();

    private final IpBanStore store;
    private final IpBanProtection protection;
    private final AliasGateway gateway;
    private final Clock clock;
    private final boolean enabled;
    private final String alias;
    private final Duration interval;
    private final long startedAt;

    private final AliasBackoff backoff = new AliasBackoff();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicLong lastRun = new AtomicLong();
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicInteger inSync = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final Counter refused;
    private final MeterRegistry meters;

    public AliasReconciler(IpBanStore store, IpBanProtection protection, AliasGateway gateway, Clock clock, MeterRegistry meters,
                           @Value("${blackhole.ipban.firewall.enabled:false}") boolean enabled,
                           @Value("${blackhole.ipban.firewall.alias:blackhole_ipban}") String alias,
                           @Value("${blackhole.ipban.firewall.reconcile-interval:PT5M}") Duration interval) {
        this.store = store;
        this.protection = protection;
        this.gateway = gateway;
        this.clock = clock;
        this.enabled = enabled;
        this.alias = alias;
        this.interval = interval;
        this.meters = meters;
        this.startedAt = clock.millis();
        this.refused = Counter.builder("blackhole.ipban.alias.refused")
                .description("IP ban rows the firewall adapter refused because they overlap a protected network")
                .register(meters);
        Gauge.builder("blackhole.ipban.alias.in_sync", this.inSync, AtomicInteger::get)
                .description("1 when the last reconciliation read back what it wrote").register(meters);
        Gauge.builder("blackhole.ipban.alias.pending", this.pending, AtomicInteger::get)
                .description("Entries the last reconciliation had to add or delete").register(meters);
        Gauge.builder("blackhole.ipban.alias.last_success_seconds", this.lastSuccess, value -> value.get() / 1000.0)
                .description("Epoch seconds of the last successful reconciliation, 0 when there was none").register(meters);
    }

    @Override
    public void changed() {
        this.dirty.set(true);
    }

    /** Runs the reconciliation when one is due; called by the scheduler every five seconds. */
    @Scheduled(fixedDelay = "5s")
    void tick() {
        if (!this.enabled) {
            return;
        }
        long now = this.clock.millis();
        long since = now - this.lastRun.get();
        boolean due = since >= this.interval.toMillis() || (this.dirty.get() && since >= DEBOUNCE_MILLIS);
        if (due) {
            reconcile();
        }
    }

    /** One reconciliation run. Public for the start-up hook and the tests. */
    public Outcome reconcile() {
        if (!this.enabled) {
            return Outcome.DISABLED;
        }
        long now = this.clock.millis();
        this.lastRun.set(now);
        this.dirty.set(false);
        if (this.backoff.waiting(now)) {
            return Outcome.SKIPPED;
        }
        try {
            Set<String> desired = desiredRanges(now);
            AliasDiff diff = AliasDiff.of(desired, normalized(this.gateway.entries(this.alias)));
            this.pending.set(diff.size());
            if (!diff.isEmpty()) {
                write(diff);
                Set<String> after = normalized(this.gateway.entries(this.alias));
                if (!after.equals(desired)) {
                    throw new AliasException("read_back", "the alias read back differs from the database");
                }
                this.pending.set(0);
            }
            this.inSync.set(1);
            this.lastSuccess.set(now);
            this.backoff.succeeded();
            return Outcome.IN_SYNC;
        } catch (AliasException e) {
            this.meters.counter("blackhole.ipban.alias.errors", "operation", e.operation()).increment();
            this.inSync.set(0);
            this.backoff.failed(now);
            LOGGER.warn("IP ban alias reconciliation failed at {}: {}", e.operation(), e.getMessage());
            return Outcome.FAILED;
        }
    }

    /** @return true when the firewall layer is switched on */
    public boolean enabled() {
        return this.enabled;
    }

    /** @return the reconcile interval; a DOWN check needs three of them without a success */
    public Duration interval() {
        return this.interval;
    }

    /** @return the epoch milliseconds of the last successful run, or of start-up when there was none */
    public long lastSuccessOrStart() {
        long success = this.lastSuccess.get();
        return success == 0 ? this.startedAt : success;
    }

    private void write(AliasDiff diff) {
        for (String range : diff.toAdd()) {
            this.gateway.add(this.alias, range);
        }
        for (String range : diff.toDelete()) {
            this.gateway.delete(this.alias, range);
        }
        this.gateway.reconfigure();
    }

    /** The active rows that may reach the alias. A row that overlaps a protected network is counted and skipped. */
    private Set<String> desiredRanges(long now) {
        Set<String> desired = new HashSet<>();
        for (IpBanRecord ban : this.store.active(now)) {
            IntelPrefix network = ban.network();
            if (this.protection.overlapping(network).isPresent()) {
                this.refused.increment();
                LOGGER.warn("An IP ban row overlapping a protected network was kept out of the firewall alias: {}", ban.id());
                continue;
            }
            desired.add(ban.range());
        }
        return desired;
    }

    /** Canonical text for every entry; an entry that is not an address or range is kept as typed, so it can be removed. */
    private static Set<String> normalized(Set<String> entries) {
        Set<String> result = new HashSet<>();
        for (String entry : entries) {
            result.add(IpBanNetwork.parse(entry).map(IpBanNetwork::range).orElse(entry));
        }
        return result;
    }
}
