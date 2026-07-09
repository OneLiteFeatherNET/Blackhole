package net.onelitefeather.blackhole.backend.profile;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Called as a side effect of every profile write. Invalidates this replica's own cache entry
 * immediately (no need to wait on a broker round-trip for the instance that made the write) and
 * broadcasts to every other replica via the {@code blackhole.cache.invalidate} fanout exchange.
 */
@Singleton
public class CacheInvalidationPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationPublisher.class);

    private final ProfileCache profileCache;
    private final CacheInvalidationRabbitPublisher rabbitPublisher;

    public CacheInvalidationPublisher(ProfileCache profileCache, CacheInvalidationRabbitPublisher rabbitPublisher) {
        this.profileCache = profileCache;
        this.rabbitPublisher = rabbitPublisher;
    }

    public void invalidate(String owner) {
        this.profileCache.invalidate(owner);
        try {
            this.rabbitPublisher.invalidate(new CacheInvalidationMessage(owner));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to broadcast cache invalidation for owner {}: {}", owner, e.getMessage());
        }
    }
}
