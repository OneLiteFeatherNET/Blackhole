package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;

import java.time.Duration;
import java.util.Optional;

/**
 * Shared-cache entry for a player's ELO standing, read by the dashboard. Every ELO write (a delta
 * or a decay reconciliation) evicts it.
 */
@Singleton
public class EloProfileCache {

    private final SharedCache sharedCache;
    private final Duration ttl;

    public EloProfileCache(SharedCache sharedCache, @Value("${blackhole.cache.elo.ttl:PT30S}") Duration ttl) {
        this.sharedCache = sharedCache;
        this.ttl = ttl;
    }

    public Optional<EloDTO.ProfileResponse> get(String owner) {
        return this.sharedCache.get(CacheKeys.elo(owner), Argument.of(EloDTO.ProfileResponse.class));
    }

    public void put(String owner, EloDTO.ProfileResponse profile) {
        this.sharedCache.put(CacheKeys.elo(owner), Argument.of(EloDTO.ProfileResponse.class), profile, this.ttl);
    }

    public void evict(String owner) {
        this.sharedCache.evict(CacheKeys.elo(owner));
    }
}
