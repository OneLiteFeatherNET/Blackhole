package net.onelitefeather.blackhole.backend.evasion.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evasion.ClientFingerprintHasher;
import net.onelitefeather.blackhole.backend.evasion.ClientFingerprintStore;
import net.onelitefeather.blackhole.backend.evasion.ClientFingerprintTraits;
import net.onelitefeather.blackhole.backend.evasion.dto.ClientFingerprintDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The client fingerprint signal for ban evasion, next to {@link IpCorrelationService}: the proxy sends
 * the traits, this service hashes them with the fingerprint key, stores only the keyed hash with the
 * hashed owner, and links owners that share a fingerprint.
 *
 * <p>It is a weaker signal than the IP: a fingerprint shared by more than {@code max-owners} owners inside
 * the window (all vanilla clients, a popular modpack) is <i>common</i> and links nobody; fingerprints of
 * plain vanilla, fabric, forge or neoforge clients are never stored. Nothing here punishes: no rule action
 * and nothing in the ELO engine reads a fingerprint match.</p>
 */
@Singleton
public class ClientFingerprintService {

    private final ClientFingerprintStore store;
    private final ClientFingerprintHasher hasher;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final Duration window;
    private final int maxOwners;

    /** Another owner that shares at least one non-common fingerprint with the requested one. */
    public record Link(String owner, int sharedFingerprints, long firstSeen, long lastSeen) {
    }

    public ClientFingerprintService(
            ClientFingerprintStore store,
            ClientFingerprintHasher hasher,
            DomainEventPublisher events,
            Clock clock,
            @Value("${blackhole.evasion.fingerprint.window-days:14}") int windowDays,
            @Value("${blackhole.evasion.fingerprint.max-owners:5}") int maxOwners
    ) {
        this.store = store;
        this.hasher = hasher;
        this.events = events;
        this.clock = clock;
        this.window = Duration.ofDays(windowDays);
        this.maxOwners = maxOwners;
    }

    /** Whether fingerprinting is on (a key is configured, or a local environment generated one). */
    public boolean isEnabled() {
        return this.hasher.isEnabled();
    }

    /** The rolling window inside which sightings of different owners count as linked. */
    public Duration window() {
        return this.window;
    }

    /**
     * Hashes and stores the traits of an owner and publishes {@code evasion.client_match} when the
     * fingerprint now links owners.
     *
     * @return {@link ClientFingerprintDTO.Response} ({@code stored} is false for generic traits), or an
     * {@link ClientFingerprintDTO.Error}: unavailable (503) when no key is configured, validation (400)
     * for an oversized brand or list
     */
    public ClientFingerprintDTO record(ClientFingerprintDTO.CreateRequest request) {
        if (!this.hasher.isEnabled()) {
            return new ClientFingerprintDTO.Error("client fingerprinting is not configured", ErrorKind.UNAVAILABLE);
        }
        ClientFingerprintTraits traits = ClientFingerprintTraits.of(request.brand(), request.channels(), request.mods()).orElse(null);
        if (traits == null) {
            return new ClientFingerprintDTO.Error("brand is limited to " + ClientFingerprintTraits.MAX_BRAND_LENGTH
                    + " characters, channels and mods to " + ClientFingerprintTraits.MAX_ENTRIES + " entries", ErrorKind.VALIDATION);
        }
        if (traits.isGeneric()) {
            return new ClientFingerprintDTO.Response(false);
        }
        String fingerprint = this.hasher.fingerprint(traits.canonical());
        long now = this.clock.millis();
        this.store.record(fingerprint, ClientFingerprintTraits.SCHEME, request.owner(), now);
        publishMatch(fingerprint, now);
        return new ClientFingerprintDTO.Response(true);
    }

    private void publishMatch(String fingerprint, long now) {
        List<String> owners = owners(this.store.ofFingerprint(fingerprint, now - this.window.toMillis()));
        if (owners.size() >= 2 && owners.size() <= this.maxOwners) {
            this.events.publish("evasion.client_match", Map.of("owners", owners, "at", now));
        }
    }

    /**
     * The other owners that share a non-common fingerprint with {@code owner} inside the window, most
     * recently seen first.
     */
    public List<Link> linksOf(String owner) {
        if (!this.hasher.isEnabled()) {
            return List.of();
        }
        long since = this.clock.millis() - this.window.toMillis();
        List<String> own = this.store.fingerprintsOf(owner, since);
        if (own.isEmpty()) {
            return List.of();
        }
        Map<String, List<ClientFingerprintStore.Sighting>> byFingerprint = this.store.ofFingerprints(own, since).stream()
                .collect(Collectors.groupingBy(ClientFingerprintStore.Sighting::fingerprint));
        Map<String, Set<String>> sharedByOwner = new LinkedHashMap<>();
        Map<String, long[]> seenByOwner = new LinkedHashMap<>();
        for (Map.Entry<String, List<ClientFingerprintStore.Sighting>> entry : byFingerprint.entrySet()) {
            if (owners(entry.getValue()).size() > this.maxOwners) {
                continue;
            }
            for (ClientFingerprintStore.Sighting other : entry.getValue()) {
                if (owner.equals(other.owner())) {
                    continue;
                }
                sharedByOwner.computeIfAbsent(other.owner(), key -> new HashSet<>()).add(entry.getKey());
                seenByOwner.merge(other.owner(), new long[]{other.firstSeen(), other.lastSeen()},
                        (a, b) -> new long[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            }
        }
        List<Link> links = new ArrayList<>();
        seenByOwner.forEach((other, seen) -> links.add(new Link(other, sharedByOwner.get(other).size(), seen[0], seen[1])));
        links.sort(Comparator.comparingLong(Link::lastSeen).reversed().thenComparing(Link::owner));
        return links;
    }

    private static List<String> owners(List<ClientFingerprintStore.Sighting> sightings) {
        return sightings.stream().map(ClientFingerprintStore.Sighting::owner).distinct().sorted().toList();
    }
}
