package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientRuleAction;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.client.model.ClientRuleResponse;
import net.onelitefeather.blackhole.client.model.ClientRuleScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static net.onelitefeather.blackhole.velocity.clientrule.Rules.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleRegistryTest {

    private static final class Source implements RuleRegistry.Source {
        Optional<List<ClientRule>> cache = Optional.empty();
        List<ClientRule> api = List.of();
        Exception apiFailure;
        int apiCalls;

        @Override
        public Optional<List<ClientRule>> fromCache() {
            return this.cache;
        }

        @Override
        public List<ClientRule> fromApi() throws Exception {
            this.apiCalls++;
            if (this.apiFailure != null) {
                throw this.apiFailure;
            }
            return this.api;
        }
    }

    private final Source source = new Source();
    private final RuleRegistry registry = new RuleRegistry(source);

    private static ClientRule some(String key) {
        return rule(key, ClientRuleKind.BRAND).patterns("x*").build();
    }

    @Test
    void beforeTheFirstLoadNothingIsEnforced() {
        assertEquals(List.of(), registry.rules());
        assertFalse(registry.loaded());
    }

    @Test
    void theSharedCacheIsPreferredAndTheApiIsNotCalledThen() {
        source.cache = Optional.of(List.of(some("a")));

        assertTrue(registry.reload());

        assertEquals(List.of("a"), registry.rules().stream().map(ClientRule::key).toList());
        assertEquals(0, source.apiCalls);
    }

    @Test
    void aCacheMissFallsBackToTheApi() {
        source.api = List.of(some("b"));

        registry.reload();

        assertEquals(List.of("b"), registry.rules().stream().map(ClientRule::key).toList());
    }

    @Test
    void aFailedReloadKeepsThePreviousSnapshot() {
        source.cache = Optional.of(List.of(some("a")));
        registry.reload();
        source.cache = Optional.empty();
        source.apiFailure = new ApiException(503, "down");

        assertFalse(registry.reload());

        assertEquals(1, registry.rules().size());
    }

    @Test
    void aBackendThatCannotBeReachedAtStartLeavesNothingEnforced() {
        source.apiFailure = new ApiException(503, "down");

        assertFalse(registry.reload());
        assertFalse(registry.reload());

        assertEquals(List.of(), registry.rules());
    }

    @Test
    void aReloadReplacesTheRulesWithTheNewList() {
        source.cache = Optional.of(List.of(some("a"), some("b")));
        registry.reload();
        source.cache = Optional.of(List.of(some("b")));

        registry.reload();

        assertEquals(List.of("b"), registry.rules().stream().map(ClientRule::key).toList());
    }

    // ---- the backend source ------------------------------------------------------------

    @Test
    void anOlderBackendThatAnswers404MeansNoRules() throws Exception {
        FakeClientRuleBackend backend = new FakeClientRuleBackend();
        backend.rulesFailure = new ApiException(404, "not found");

        assertEquals(List.of(), new BackendRuleSource(backend).fromApi());
    }

    @Test
    void otherBackendFailuresPropagateSoTheRegistryKeepsWhatItHas() {
        FakeClientRuleBackend backend = new FakeClientRuleBackend();
        backend.rulesFailure = new ApiException(500, "boom");

        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> new BackendRuleSource(backend).fromApi());
    }

    @Test
    void onlyEnabledRulesAreTakenFromTheApi() throws Exception {
        FakeClientRuleBackend backend = new FakeClientRuleBackend();
        backend.rules = List.of(response("on", true), response("off", false));

        assertEquals(List.of("on"), new BackendRuleSource(backend).fromApi().stream().map(ClientRule::key).toList());
    }

    @Test
    void theProxyNoLongerReadsAnySharedCache() {
        assertTrue(new BackendRuleSource(new FakeClientRuleBackend()).fromCache().isEmpty());
    }

    private static ClientRuleResponse response(String key, boolean enabled) {
        return new ClientRuleResponse().key(key).displayName(key).kind(ClientRuleKind.BRAND).enabled(enabled).patterns(List.of("x*"))
                .scope(ClientRuleScope.GLOBAL).action(ClientRuleAction.NOTIFY);
    }
}
