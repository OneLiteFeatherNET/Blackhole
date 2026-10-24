package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientRuleResponse;
import net.onelitefeather.blackhole.velocity.redis.SharedCacheReader;
import net.onelitefeather.blackhole.velocity.redis.SyncCodec;

import java.util.List;
import java.util.Optional;

/**
 * {@link RuleRegistry.Source} backed by the backend's shared cache (read through Redis) and its rule
 * API. A backend that answers 404 predates the routes: that means "no rules", not a failure.
 */
public final class BackendRuleSource implements RuleRegistry.Source {

    /** The enabled rules in the backend's shared cache; part of the hand-shared Redis contract. */
    public static final String CACHE_KEY = "blackhole:cache:v1:client-rules:all";

    private final SharedCacheReader cache;
    private final ClientRuleBackend backend;
    private final SyncCodec codec = new SyncCodec();

    public BackendRuleSource(SharedCacheReader cache, ClientRuleBackend backend) {
        this.cache = cache;
        this.backend = backend;
    }

    @Override
    public Optional<List<ClientRule>> fromCache() {
        return this.cache.readShared(CACHE_KEY).flatMap(this.codec::clientRules);
    }

    @Override
    public List<ClientRule> fromApi() throws ApiException {
        try {
            return this.backend.rules().stream().filter(rule -> Boolean.TRUE.equals(rule.getEnabled())).map(ClientRule::of).toList();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return List.of();
            }
            throw e;
        }
    }
}
