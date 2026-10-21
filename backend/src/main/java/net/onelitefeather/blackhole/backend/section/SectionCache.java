package net.onelitefeather.blackhole.backend.section;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Shared-cache entries for sections: each section by key (read on every ELO write and punishment
 * apply) and the full list that proxies read. Every section write evicts both.
 */
@Singleton
public class SectionCache {

    private final SharedCache sharedCache;
    private final Duration ttl;

    public SectionCache(SharedCache sharedCache, @Value("${blackhole.cache.section.ttl:PT5M}") Duration ttl) {
        this.sharedCache = sharedCache;
        this.ttl = ttl;
    }

    public Optional<SectionDTO.Response> get(String key) {
        return this.sharedCache.get(CacheKeys.section(key), Argument.of(SectionDTO.Response.class));
    }

    public void put(SectionDTO.Response section) {
        this.sharedCache.put(CacheKeys.section(section.key()), Argument.of(SectionDTO.Response.class), section, this.ttl);
    }

    public Optional<List<SectionDTO.Response>> getAll() {
        return this.sharedCache.get(CacheKeys.SECTIONS_ALL, Argument.listOf(SectionDTO.Response.class));
    }

    /**
     * Stores the full section list under {@link CacheKeys#SECTIONS_ALL}, as the JSON array the API returns.
     */
    public void putAll(List<SectionDTO.Response> sections) {
        this.sharedCache.put(CacheKeys.SECTIONS_ALL, Argument.listOf(SectionDTO.Response.class), sections, this.ttl);
    }

    /**
     * Evicts one section and the full list, so a write is visible on the next read of either.
     */
    public void evict(String key) {
        this.sharedCache.evict(CacheKeys.section(key), CacheKeys.SECTIONS_ALL);
    }
}
