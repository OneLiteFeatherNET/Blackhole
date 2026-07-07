package net.onelitefeather.blackhole.backend.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileId;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory cache for the profile-by-owner hot path (hit on every Velocity login and every chat
 * message). Bounded by a short TTL so a missed cross-replica invalidation only causes brief
 * staleness rather than a permanently stale read; see {@code CacheInvalidationPublisher} /
 * {@code CacheInvalidationConsumer} for the RabbitMQ fanout that keeps replicas in sync on writes.
 */
@Singleton
public class ProfileCache {

    private final Cache<PunishmentProfileId, PunishmentProfileEntity> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public Optional<PunishmentProfileEntity> get(PunishmentProfileId id) {
        return Optional.ofNullable(this.cache.getIfPresent(id));
    }

    public void put(PunishmentProfileId id, PunishmentProfileEntity entity) {
        this.cache.put(id, entity);
    }

    public void invalidate(PunishmentProfileId id) {
        this.cache.invalidate(id);
    }

    public void invalidate(UUID tenantId, String owner) {
        invalidate(new PunishmentProfileId(tenantId, owner));
    }
}
