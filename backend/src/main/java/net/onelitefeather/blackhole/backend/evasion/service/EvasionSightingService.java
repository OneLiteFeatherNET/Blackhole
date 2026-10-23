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

    private final IpCorrelationTokenRepository repository;
    private final IpCorrelationService correlation;
    private final Clock clock;

    public EvasionSightingService(IpCorrelationTokenRepository repository, IpCorrelationService correlation, Clock clock) {
        this.repository = repository;
        this.correlation = correlation;
        this.clock = clock;
    }

    public EvasionSightingDTO forOwner(String owner) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            return new EvasionSightingDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        Duration window = this.correlation.detectionWindow();
        int windowDays = (int) window.toDays();
        if (!this.correlation.isConfigured()) {
            return new EvasionSightingDTO.Response(false, windowDays, List.of(), false);
        }

        long windowStart = this.clock.millis() - window.toMillis();
        Set<String> tokens = new HashSet<>();
        for (IpCorrelationTokenEntity own : this.repository.findByOwnerHash(owner)) {
            if (own.getLastSeen() >= windowStart) {
                tokens.add(own.getToken());
            }
        }
        if (tokens.isEmpty()) {
            return new EvasionSightingDTO.Response(true, windowDays, List.of(), false);
        }

        Map<String, Set<String>> addressesByOwner = new LinkedHashMap<>();
        Map<String, long[]> seenByOwner = new LinkedHashMap<>();
        for (IpCorrelationTokenEntity other : this.repository.findByTokenInAndLastSeenGreaterThanEquals(tokens, windowStart)) {
            if (owner.equals(other.getOwnerHash())) {
                continue;
            }
            addressesByOwner.computeIfAbsent(other.getOwnerHash(), key -> new HashSet<>()).add(other.getToken());
            seenByOwner.merge(other.getOwnerHash(), new long[]{other.getFirstSeen(), other.getLastSeen()},
                    (a, b) -> new long[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }

        List<EvasionSightingDTO.LinkedOwner> linked = seenByOwner.entrySet().stream()
                .map(entry -> new EvasionSightingDTO.LinkedOwner(entry.getKey(), REASON_SHARED_IP,
                        addressesByOwner.get(entry.getKey()).size(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingLong(EvasionSightingDTO.LinkedOwner::lastSeen).reversed()
                        .thenComparing(EvasionSightingDTO.LinkedOwner::owner))
                .toList();
        boolean truncated = linked.size() > MAX_LINKED_OWNERS;
        return new EvasionSightingDTO.Response(true, windowDays,
                truncated ? linked.subList(0, MAX_LINKED_OWNERS) : linked, truncated);
    }
}
