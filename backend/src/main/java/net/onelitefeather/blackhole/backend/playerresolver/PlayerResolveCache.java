package net.onelitefeather.blackhole.backend.playerresolver;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;

import java.time.Duration;
import java.util.Optional;

/**
 * Shared-cache view of resolved name-to-UUID lookups, keyed by the lowercased name. Only successful
 * resolutions are stored (see {@code PlayerResolverService}), and resolved-name data barely changes,
 * so a TTL is the only staleness bound and no cross-replica eviction is needed.
 */
@Singleton
public class PlayerResolveCache {

    private final SharedCache sharedCache;
    private final Duration ttl;

    public PlayerResolveCache(SharedCache sharedCache, @Value("${blackhole.player-resolver.cache.ttl}") Duration ttl) {
        this.sharedCache = sharedCache;
        this.ttl = ttl;
    }

    public Optional<ResolvedPlayer> get(String name) {
        return this.sharedCache.get(CacheKeys.player(name), Argument.of(ResolvedPlayer.class));
    }

    public void put(String name, ResolvedPlayer resolved) {
        this.sharedCache.put(CacheKeys.player(name), Argument.of(ResolvedPlayer.class), resolved, this.ttl);
    }
}
