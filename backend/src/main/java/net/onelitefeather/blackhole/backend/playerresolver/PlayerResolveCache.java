package net.onelitefeather.blackhole.backend.playerresolver;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.Optional;

/**
 * In-memory cache for resolved name-to-UUID lookups, keyed by lowercased name. Mirrors
 * {@code ProfileCache}'s shape but, unlike it, isn't wired into the cross-replica invalidation
 * fanout - resolved-name data barely ever changes, so a short TTL is a sufficient staleness bound
 * on its own.
 */
@Singleton
public class PlayerResolveCache {

    private final Cache<String, ResolvedPlayer> cache;

    public PlayerResolveCache(
            @Value("${blackhole.player-resolver.cache.ttl}") Duration ttl,
            @Value("${blackhole.player-resolver.cache.max-size}") long maxSize
    ) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
    }

    public Optional<ResolvedPlayer> get(String name) {
        return Optional.ofNullable(this.cache.getIfPresent(name.toLowerCase()));
    }

    public void put(String name, ResolvedPlayer resolved) {
        this.cache.put(name.toLowerCase(), resolved);
    }
}
