package net.onelitefeather.blackhole.backend.evasion.service;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evasion.IpCorrelationTokenEntity;
import net.onelitefeather.blackhole.backend.evasion.IpCorrelationTokenRepository;
import net.onelitefeather.blackhole.backend.evasion.dto.EvasionSightingDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the evasion sightings of one owner back as the other owners that share a hashed address
 * with it, using the same rolling window as the live detection in {@link IpCorrelationService}.
 * The token itself (the keyed hash of the IP) stays inside the backend.
 */
@Singleton
public class EvasionSightingService {

    static final int MAX_LINKED_OWNERS = 50;
    private static final Pattern OWNER = Pattern.compile("^[a-fA-F0-9]{128}$");
    private static final String REASON_SHARED_IP = "SHARED_IP";
    private static final String REASON_SHARED_CLIENT = "SHARED_CLIENT";

    private final IpCorrelationTokenRepository repository;
    private final IpCorrelationService correlation;
    private final ClientFingerprintService fingerprints;
    private final Clock clock;

    public EvasionSightingService(IpCorrelationTokenRepository repository, IpCorrelationService correlation,
                                  ClientFingerprintService fingerprints, Clock clock) {
        this.repository = repository;
        this.correlation = correlation;
        this.fingerprints = fingerprints;
        this.clock = clock;
    }

    public EvasionSightingDTO forOwner(String owner) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            return new EvasionSightingDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        int windowDays = (int) this.correlation.detectionWindow().toDays();
        int clientWindowDays = (int) this.fingerprints.window().toDays();
        boolean ipEnabled = this.correlation.isConfigured();
        boolean clientEnabled = this.fingerprints.isEnabled();

        Map<String, long[]> ip = ipEnabled ? ipLinks(owner) : Map.of();
        Map<String, ClientFingerprintService.Link> client = new LinkedHashMap<>();
        this.fingerprints.linksOf(owner).forEach(link -> client.put(link.owner(), link));

        Set<String> owners = new java.util.LinkedHashSet<>(ip.keySet());
        owners.addAll(client.keySet());
        List<EvasionSightingDTO.LinkedOwner> linked = owners.stream().map(other -> {
            long[] byIp = ip.get(other);
            ClientFingerprintService.Link byClient = client.get(other);
            long first = Math.min(byIp == null ? Long.MAX_VALUE : byIp[0], byClient == null ? Long.MAX_VALUE : byClient.firstSeen());
            long last = Math.max(byIp == null ? Long.MIN_VALUE : byIp[1], byClient == null ? Long.MIN_VALUE : byClient.lastSeen());
            EvasionSightingDTO.Via via = byIp != null && byClient != null ? EvasionSightingDTO.Via.BOTH
                    : byIp != null ? EvasionSightingDTO.Via.IP : EvasionSightingDTO.Via.CLIENT;
            return new EvasionSightingDTO.LinkedOwner(other, byIp != null ? REASON_SHARED_IP : REASON_SHARED_CLIENT,
                    byIp != null ? (int) byIp[2] : 0, first, last, via);
        }).sorted(Comparator.comparingLong(EvasionSightingDTO.LinkedOwner::lastSeen).reversed()
                .thenComparing(EvasionSightingDTO.LinkedOwner::owner)).toList();
        boolean truncated = linked.size() > MAX_LINKED_OWNERS;
        return new EvasionSightingDTO.Response(ipEnabled, windowDays,
                truncated ? linked.subList(0, MAX_LINKED_OWNERS) : linked, truncated, clientEnabled, clientWindowDays);
    }

    /** Other owners on a shared hashed address: owner to {first seen, last seen, shared addresses}. */
    private Map<String, long[]> ipLinks(String owner) {
        long windowStart = this.clock.millis() - this.correlation.detectionWindow().toMillis();
        Set<String> tokens = new HashSet<>();
        for (IpCorrelationTokenEntity own : this.repository.findByOwnerHash(owner)) {
            if (own.getLastSeen() >= windowStart) {
                tokens.add(own.getToken());
            }
        }
        if (tokens.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> addressesByOwner = new LinkedHashMap<>();
        Map<String, long[]> seenByOwner = new LinkedHashMap<>();
        for (IpCorrelationTokenEntity other : this.repository.findByTokenInAndLastSeenGreaterThanEquals(tokens, windowStart)) {
            if (owner.equals(other.getOwnerHash())) {
                continue;
            }
            addressesByOwner.computeIfAbsent(other.getOwnerHash(), key -> new HashSet<>()).add(other.getToken());
            seenByOwner.merge(other.getOwnerHash(), new long[]{other.getFirstSeen(), other.getLastSeen(), 0},
                    (a, b) -> new long[]{Math.min(a[0], b[0]), Math.max(a[1], b[1]), 0});
        }
        seenByOwner.forEach((other, seen) -> seen[2] = addressesByOwner.get(other).size());
        return seenByOwner;
    }
}
