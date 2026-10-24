package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.SharedCache;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleDTO;

import java.time.Duration;
import java.util.List;

/**
 * The enabled rules in the shared cache, which is where proxies read them (they call the endpoint
 * only when the entry is missing). Listing the rules refreshes it, every rule write evicts it.
 */
@Singleton
public class ClientRuleCache {

    private final SharedCache sharedCache;
    private final Duration ttl;

    public ClientRuleCache(SharedCache sharedCache, @Value("${blackhole.cache.client-rule.ttl:PT5M}") Duration ttl) {
        this.sharedCache = sharedCache;
        this.ttl = ttl;
    }

    /** Stores the enabled rules as the JSON array the API returns. */
    public void putEnabled(List<ClientRuleDTO.Response> enabledRules) {
        this.sharedCache.put(CacheKeys.CLIENT_RULES_ALL, Argument.listOf(ClientRuleDTO.Response.class), enabledRules, this.ttl);
    }

    /** Evicts the mirror, so a write is visible to the next proxy read. */
    public void evict() {
        this.sharedCache.evict(CacheKeys.CLIENT_RULES_ALL);
    }
}
