package net.onelitefeather.blackhole.backend.playerresolver.service;

import net.onelitefeather.blackhole.backend.playerresolver.PlayerResolveCache;
import net.onelitefeather.blackhole.backend.playerresolver.PlayerResolver;
import net.onelitefeather.blackhole.backend.playerresolver.ResolvedPlayer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Walks every enabled {@link PlayerResolver} in {@link io.micronaut.core.annotation.Order}
 * order and returns the first hit. Micronaut injects the list already sorted, so adding another
 * resolver later is just another {@code @Singleton} bean - no change here.
 */
@Singleton
public class PlayerResolverService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerResolverService.class);

    private final List<PlayerResolver> resolvers;
    private final PlayerResolveCache cache;

    public PlayerResolverService(List<PlayerResolver> resolvers, PlayerResolveCache cache) {
        this.resolvers = resolvers;
        this.cache = cache;
    }

    public Optional<ResolvedPlayer> resolve(String name) {
        Optional<ResolvedPlayer> cached = this.cache.get(name);
        if (cached.isPresent()) {
            return cached;
        }

        for (PlayerResolver resolver : this.resolvers) {
            Optional<ResolvedPlayer> result;
            try {
                result = resolver.resolve(name);
            } catch (Exception e) {
                LOGGER.warn("{} threw while resolving player {}: {}", resolver.getClass().getSimpleName(), name, e.getMessage());
                continue;
            }
            if (result.isPresent()) {
                this.cache.put(name, result.get());
                return result;
            }
        }
        return Optional.empty();
    }
}
