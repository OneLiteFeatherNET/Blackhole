package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;

import java.util.List;
import java.util.Optional;

/**
 * {@link RuleRegistry.Source} backed by the backend's rule API. The proxy keeps no shared cache, so
 * {@link #fromCache} never answers and every reload reads the API. A backend that answers 404 predates
 * the routes: that means "no rules", not a failure.
 */
public final class BackendRuleSource implements RuleRegistry.Source {

    private final ClientRuleBackend backend;

    public BackendRuleSource(ClientRuleBackend backend) {
        this.backend = backend;
    }

    @Override
    public Optional<List<ClientRule>> fromCache() {
        return Optional.empty();
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
