package net.onelitefeather.blackhole.backend.proxy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.proxy.dto.ProxyCapabilityDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Keeps each proxy's last SignedVelocity report in the shared cache, so any replica answers. A report
 * is kept for a day so that a proxy which stops reporting is still listed, as unknown, rather than
 * vanishing from the panel. The state is decided on read: a report older than the stale window is
 * unknown, never present.
 *
 * <p>Metric {@code blackhole.proxy.signedvelocity} per proxy: 1 present, 0 absent, -1 unknown.</p>
 */
@Singleton
public class ProxyCapabilityService {

    static final String METRIC = "blackhole.proxy.signedvelocity";
    static final Duration RETAIN = Duration.ofDays(1);

    private static final Pattern PROXY_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._+-]{1,64}");

    private final SharedCache cache;
    private final Clock clock;
    private final Duration staleAfter;
    private final MeterRegistry meters;

    public ProxyCapabilityService(
            SharedCache cache,
            Clock clock,
            @Value("${blackhole.proxy.capabilities.stale-after:PT10M}") Duration staleAfter,
            MeterRegistry meters
    ) {
        this.cache = cache;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.meters = meters;
    }

    /**
     * Replaces the proxy's last report.
     *
     * @return {@link ProxyCapabilityDTO.Stored}, or an {@link ProxyCapabilityDTO.Error} if the report is
     * malformed (400) or the cache is down and the proxy should retry (503)
     */
    public ProxyCapabilityDTO report(ProxyCapabilityDTO.Report request) {
        String problem = validate(request);
        if (problem != null) {
            return new ProxyCapabilityDTO.Error(problem, ErrorKind.VALIDATION);
        }
        ProxyCapabilityDTO.SignedVelocity relay = request.signedVelocity();
        String version = relay.present() ? relay.version() : null;
        Instant now = this.clock.instant();
        ProxyCapabilityRecord record = new ProxyCapabilityRecord(request.proxyId(), relay.present(), version, now);
        if (!this.cache.tryPut(CacheKeys.proxyCapability(request.proxyId()), Argument.of(ProxyCapabilityRecord.class), record, RETAIN)) {
            return new ProxyCapabilityDTO.Error("the SignedVelocity state could not be stored, retry later", ErrorKind.UNAVAILABLE);
        }
        Gauge.builder(METRIC, this, service -> service.gaugeValue(request.proxyId()))
                .tag("proxy", request.proxyId())
                .register(this.meters);
        return new ProxyCapabilityDTO.Stored(request.proxyId(), now);
    }

    /**
     * @return the last report of every proxy that has reported and is still retained
     */
    public ProxyCapabilityDTO.Response capabilities() {
        Instant now = this.clock.instant();
        List<ProxyCapabilityDTO.Relay> proxies = new ArrayList<>();
        for (String key : this.cache.keys(CacheKeys.PROXY_CAPABILITY_PATTERN).orElse(List.of())) {
            this.cache.get(key, Argument.of(ProxyCapabilityRecord.class))
                    .map(record -> new ProxyCapabilityDTO.Relay(record.proxyId(), stateOf(record, now), record.version(), record.reportedAt()))
                    .ifPresent(proxies::add);
        }
        proxies.sort(Comparator.comparing(ProxyCapabilityDTO.Relay::proxyId));
        return new ProxyCapabilityDTO.Response(List.copyOf(proxies));
    }

    /** The metric's value for one proxy: 1 present, 0 absent, -1 unknown (never reported, or the cache is down). */
    double gaugeValue(String proxyId) {
        Optional<ProxyCapabilityRecord> record = this.cache.get(CacheKeys.proxyCapability(proxyId), Argument.of(ProxyCapabilityRecord.class));
        if (record.isEmpty()) {
            return -1;
        }
        return switch (stateOf(record.get(), this.clock.instant())) {
            case PRESENT -> 1;
            case ABSENT -> 0;
            case UNKNOWN -> -1;
        };
    }

    private ProxyCapabilityDTO.RelayState stateOf(ProxyCapabilityRecord record, Instant now) {
        if (record.reportedAt() == null || record.reportedAt().isBefore(now.minus(this.staleAfter))) {
            return ProxyCapabilityDTO.RelayState.UNKNOWN;
        }
        return record.present() ? ProxyCapabilityDTO.RelayState.PRESENT : ProxyCapabilityDTO.RelayState.ABSENT;
    }

    private static String validate(ProxyCapabilityDTO.Report request) {
        if (request.proxyId() == null || !PROXY_ID.matcher(request.proxyId()).matches()) {
            return "proxyId must be 1-64 characters of A-Z a-z 0-9 _ . -";
        }
        ProxyCapabilityDTO.SignedVelocity relay = request.signedVelocity();
        if (relay == null) {
            return "signedVelocity is required";
        }
        if (relay.present() && relay.version() != null && !VERSION.matcher(relay.version()).matches()) {
            return "signedVelocity.version must be 1-64 characters of A-Z a-z 0-9 . _ + -";
        }
        return null;
    }
}
