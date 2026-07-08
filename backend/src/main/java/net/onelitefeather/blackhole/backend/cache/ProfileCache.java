package net.onelitefeather.blackhole.backend.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;

import java.time.Duration;
import java.util.Optional;

/**
 * In-memory cache for the profile-by-owner hot path (hit on every Velocity login and every chat
 * message). Bounded by a short TTL so a missed cross-replica invalidation only causes brief
 * staleness rather than a permanently stale read; see {@code CacheInvalidationPublisher} /
 * {@code CacheInvalidationConsumer} for the RabbitMQ fanout that keeps replicas in sync on writes.
 */
@Singleton
public class ProfileCache {

    private final Cache<String, PunishmentProfileEntity> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public Optional<PunishmentProfileEntity> get(String owner) {
        return Optional.ofNullable(this.cache.getIfPresent(owner));
    }

    public void put(String owner, PunishmentProfileEntity entity) {
        this.cache.put(owner, entity);
    }

    public void invalidate(String owner) {
        this.cache.invalidate(owner);
    }
}
