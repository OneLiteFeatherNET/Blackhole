package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.IntelPrefix;
import net.onelitefeather.blackhole.backend.ipban.IpBanChanges;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import net.onelitefeather.blackhole.backend.ipban.IpBanRecord;
import net.onelitefeather.blackhole.backend.ipban.IpBanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds both feeds in memory. A request never reads the database: it gets the last build.
 *
 * <p>A build happens only when something the feeds hold may have changed: an IP ban was written or lifted on this replica
 * ({@link #changed()}), a ban was written or lifted on another replica (the {@code ipban.changed} event reaches
 * {@link #changed()} through {@link FeedChangeConsumer}; if that event is lost, the marker check catches it), a network source
 * reports a new {@link FeedNetworkSource#version()}, or an entry expired since the last build. The scheduled {@link #tick()}
 * reads one {@link IpBanStore.Marker} of the ban table and the source versions, and rebuilds only when one of them differs. A
 * failed build keeps the last good feed and stays dirty, so the next tick retries. Before the first good build there is no
 * feed, and {@link #current()} is empty (the route answers 503).
 */
@Singleton
public class FeedCache implements IpBanChanges {

    private static final Logger LOG = LoggerFactory.getLogger(FeedCache.class);

    /** One body and its strong ETag, the ETag of the exact bytes. */
    public record Variant(byte[] body, String etag) {
    }

    /** One feed in both modes: with comment lines and without. */
    public record Feed(Variant withComments, Variant withoutComments) {

        public Variant variant(boolean comments) {
            return comments ? this.withComments : this.withoutComments;
        }
    }

    /** A complete build: the single-address feed and the networks feed. */
    public record Built(Feed ips, Feed networks) {

        public Feed feed(String name) {
            return "ips".equals(name) ? this.ips : this.networks;
        }
    }

    private final IpBanStore store;
    private final IpBanProtection protection;
    private final List<FeedNetworkSource> sources;
    private final Clock clock;
    private final Counter buildErrors;
    private final Counter refused;
    private final Timer buildTimer;
    private final AtomicLong ipsEntries = new AtomicLong();
    private final AtomicLong networkEntries = new AtomicLong();
    private volatile Built current;
    private boolean dirty = true;
    private List<Object> builtVersions = List.of();
    private IpBanStore.Marker builtMarker;
    private long nextExpiry = Long.MAX_VALUE;

    @Inject
    public FeedCache(IpBanStore store, IpBanProtection protection, List<FeedNetworkSource> sources, Clock clock,
                     MeterRegistry meters) {
        this.store = store;
        this.protection = protection;
        this.sources = sources;
        this.clock = clock;
        this.buildErrors = meters.counter("blackhole.ipban.feed.build_errors");
        this.refused = meters.counter("blackhole.ipban.feed.refused");
        this.buildTimer = meters.timer("blackhole.ipban.feed.build");
        meters.gauge("blackhole.ipban.feed.entries", Tags.of("feed", "ips"), this.ipsEntries, AtomicLong::doubleValue);
        meters.gauge("blackhole.ipban.feed.entries", Tags.of("feed", "networks"), this.networkEntries, AtomicLong::doubleValue);
    }

    /**
     * @return the last good build, building it first when none exists yet; empty when no build has succeeded
     */
    public Optional<Built> current() {
        Built built = this.current;
        if (built != null) {
            return Optional.of(built);
        }
        tick();
        return Optional.ofNullable(this.current);
    }

    /** A ban was written or lifted: the feeds are dirty, and are rebuilt now, on the write path. */
    @Override
    public synchronized void changed() {
        this.dirty = true;
        tick();
    }

    /**
     * Rebuilds the feeds when they may be stale (see the class comment), and does nothing otherwise. The marker of the ban
     * table is read on every tick, as one aggregate. A failure is counted and logged; the last good feed stays in place.
     */
    @Scheduled(fixedDelay = "${blackhole.firewall.feed.rebuild-interval:PT60S}",
            initialDelay = "${blackhole.firewall.feed.rebuild-interval:PT60S}")
    public synchronized void tick() {
        try {
            IpBanStore.Marker seen = this.store.marker();
            if (!seen.equals(this.builtMarker)) {
                this.dirty = true;
            }
            if (this.current == null || this.dirty || this.clock.millis() >= this.nextExpiry
                    || !sourceVersions().equals(this.builtVersions)) {
                this.buildTimer.record(() -> build(seen));
            }
        } catch (RuntimeException e) {
            this.buildErrors.increment();
            LOG.warn("firewall feed build failed; the last good feed stays in place: {}", e.getClass().getSimpleName());
        }
    }

    private List<Object> sourceVersions() {
        List<Object> versions = new ArrayList<>();
        for (FeedNetworkSource source : this.sources) {
            versions.add(source.version());
        }
        return versions;
    }

    /** @param marker the marker read before this build; a change made while the build runs differs from it on the next tick */
    private void build(IpBanStore.Marker marker) {
        long now = this.clock.millis();
        // Read before the sources, so a change made while this build runs is seen by the next tick.
        List<Object> versions = sourceVersions();
        List<FeedRenderer.Entry> singles = new ArrayList<>();
        List<FeedRenderer.Entry> networks = new ArrayList<>();
        long expiry = Long.MAX_VALUE;
        for (IpBanRecord ban : this.store.active(now)) {
            FeedRenderer.Entry entry = new FeedRenderer.Entry(ban.network(), ban.source().name(), ban.id().toString(), ban.expiresAt());
            if (isSingleAddress(ban.network())) {
                singles.add(entry);
            } else {
                networks.add(entry);
            }
            if (ban.expiresAt() != null) {
                expiry = Math.min(expiry, ban.expiresAt());
            }
        }
        for (FeedNetworkSource source : this.sources) {
            for (IntelPrefix network : source.networks()) {
                networks.add(new FeedRenderer.Entry(network, source.kind(), null, null));
            }
        }
        Feed ips = feed(singles, true);
        Feed listed = feed(networks, false);
        this.current = new Built(ips, listed);
        this.builtVersions = versions;
        this.builtMarker = marker;
        this.nextExpiry = expiry;
        this.dirty = false;
        this.ipsEntries.set(singles.size());
        this.networkEntries.set(networks.size());
    }

    private Feed feed(List<FeedRenderer.Entry> entries, boolean singles) {
        FeedRenderer.Output with = FeedRenderer.render(entries, singles, true, this::isProtected);
        FeedRenderer.Output without = FeedRenderer.render(entries, singles, false, this::isProtected);
        if (with.refused() > 0) {
            this.refused.increment(with.refused());
        }
        return new Feed(variant(with.text()), variant(without.text()));
    }

    private boolean isProtected(IntelPrefix network) {
        return this.protection.overlapping(network).isPresent();
    }

    private static Variant variant(String text) {
        byte[] body = FeedRenderer.bytes(text);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return new Variant(body, "\"" + HexFormat.of().formatHex(digest) + "\"");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean isSingleAddress(IntelPrefix network) {
        return network.length() == (network.ipv4() ? 32 : 128);
    }
}
