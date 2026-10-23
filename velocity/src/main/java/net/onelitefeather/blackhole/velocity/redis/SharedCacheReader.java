package net.onelitefeather.blackhole.velocity.redis;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Read access to the backend's shared cache. The one method either returns the stored text or says
 * "unavailable" with an empty {@link Optional}, so callers fall back to the HTTP API; it is a small
 * seam so the callers can be tested without a Redis server.
 */
@FunctionalInterface
public interface SharedCacheReader {

    /**
     * @param key the full cache key, version segment included
     * @return the stored text; empty on a miss, an error, a timeout or when not connected
     */
    @NotNull Optional<String> readShared(@NotNull String key);
}
