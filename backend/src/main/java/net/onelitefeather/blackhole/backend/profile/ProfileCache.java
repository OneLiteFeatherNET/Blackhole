package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileDTO;

import java.time.Duration;
import java.util.Optional;

/**
 * Hot-path profile-by-owner cache (hit on every Velocity login and every chat message), kept in the
 * {@link SharedCache} so every replica reads the same entry. Every write to a profile calls
 * {@link #evict} after its database write, so the next read on any replica reloads it.
 */
@Singleton
public class ProfileCache {

    private final SharedCache sharedCache;
    private final Duration ttl;

    public ProfileCache(SharedCache sharedCache, @Value("${blackhole.cache.profile.ttl:PT30S}") Duration ttl) {
        this.sharedCache = sharedCache;
        this.ttl = ttl;
    }

    public Optional<PunishProfileDTO> get(String owner) {
        return this.sharedCache.get(CacheKeys.profile(owner), Argument.of(PunishProfileDTO.class));
    }

    public void put(String owner, PunishProfileDTO profile) {
        this.sharedCache.put(CacheKeys.profile(owner), Argument.of(PunishProfileDTO.class), profile, this.ttl);
    }

    public void evict(String owner) {
        this.sharedCache.evict(CacheKeys.profile(owner));
    }
}
