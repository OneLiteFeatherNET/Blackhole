package net.onelitefeather.blackhole.backend.cache;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * Broadcast on the {@code blackhole.cache.invalidate} fanout exchange whenever a profile is
 * written, so every backend replica evicts its own local {@link ProfileCache} entry.
 */
@Serdeable
public record CacheInvalidationMessage(UUID tenantId, String owner) {
}
