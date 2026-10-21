package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared-cache entries for punishment templates: each template by identifier, and the full list
 * that Velocity proxies read for command tab completion. Every template write evicts both.
 */
@Singleton
public class PunishmentTemplateCache {

    private final SharedCache sharedCache;
    private final Duration ttl;

    public PunishmentTemplateCache(SharedCache sharedCache, @Value("${blackhole.cache.template.ttl:PT5M}") Duration ttl) {
        this.sharedCache = sharedCache;
        this.ttl = ttl;
    }

    public Optional<PunishTemplateDTO.Response> get(UUID identifier) {
        return this.sharedCache.get(CacheKeys.template(identifier), Argument.of(PunishTemplateDTO.Response.class));
    }

    public void put(UUID identifier, PunishTemplateDTO.Response template) {
        this.sharedCache.put(CacheKeys.template(identifier), Argument.of(PunishTemplateDTO.Response.class), template, this.ttl);
    }

    /**
     * Stores the full template list under {@link CacheKeys#TEMPLATES_ALL}, as the JSON array the API
     * returns.
     */
    public void putAll(List<PunishTemplateDTO.Response> templates) {
        this.sharedCache.put(CacheKeys.TEMPLATES_ALL, Argument.listOf(PunishTemplateDTO.Response.class), templates, this.ttl);
    }

    /**
     * Evicts one template and the full list, so a create, update or delete is visible on the next
     * read of either.
     */
    public void evict(UUID identifier) {
        this.sharedCache.evict(CacheKeys.template(identifier), CacheKeys.TEMPLATES_ALL);
    }

    /**
     * Evicts only the full list, for a write that creates a template no one has cached by id yet.
     */
    public void evictList() {
        this.sharedCache.evict(CacheKeys.TEMPLATES_ALL);
    }
}
