package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.cache.InMemorySharedCache;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;
import net.onelitefeather.blackhole.backend.support.FakeClientRuleStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuleServiceTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final FakeClientRuleStore store = new FakeClientRuleStore();
    private final InMemorySharedCache sharedCache = new InMemorySharedCache();
    private final TestClock clock = new TestClock();
    private final List<String> notified = new ArrayList<>();
    private final Set<String> serverSections = new java.util.HashSet<>(Set.of("minigames"));
    private final ClientRuleReferences references = new ClientRuleReferences() {
        @Override
        public boolean serverSectionExists(String sectionKey) {
            return serverSections.contains(sectionKey);
        }

        @Override
        public boolean templateExists(String templateKey) {
            return store.templateKnown(templateKey);
        }
    };
    private final ClientRuleService service = new ClientRuleService(
            store, references, new ClientRuleCache(sharedCache, Duration.ofMinutes(5)), notified::add, clock);

    private static ClientRuleDTO.CreateRequest channelRule(String key) {
        return new ClientRuleDTO.CreateRequest(key, "Meteor client", ClientRuleKind.CHANNEL, List.of("Meteor-Client:*"),
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ClientRuleDTO.CreateRequest brandTemplateRule(String key, boolean acknowledged) {
        return new ClientRuleDTO.CreateRequest(key, "Brand", ClientRuleKind.BRAND, List.of("cheat*"),
                null, null, null, null, null, null, null, ClientRuleAction.TEMPLATE, null, "cheating", acknowledged, null);
    }

    private static ClientRuleDTO.UpdateRequest update(String key, ClientRuleKind kind, ClientRuleAction action, String templateKey, boolean acknowledged) {
        return new ClientRuleDTO.UpdateRequest(key, "Brand", kind, true, List.of("cheat*"),
                null, null, null, null, null, null, null, action, null, templateKey, acknowledged, null);
    }

    private ClientRuleDTO.Error expectError(ClientRuleDTO result, ErrorKind kind) {
        ClientRuleDTO.Error error = assertInstanceOf(ClientRuleDTO.Error.class, result, "expected an error but got " + result);
        assertEquals(kind, error.kind(), "kind of: " + error.errorMessage());
        return error;
    }

    @Test
    void aCreatedRuleIsStoredEnabledWithNotifyAndLowerCasePatterns() {
        ClientRuleDTO.Response created = assertInstanceOf(ClientRuleDTO.Response.class, service.create(channelRule("meteor"), ADMIN));

        assertTrue(created.enabled(), "a new rule is enabled");
        assertEquals(ClientRuleAction.NOTIFY, created.action());
        assertEquals(ClientRuleScope.GLOBAL, created.scope());
        assertEquals(List.of("meteor-client:*"), created.patterns());
        assertEquals(0, created.hitCount());
        assertEquals(1, store.all().size());
    }

    @Test
    void theCreatingAdminIsRecordedAndTimestampsComeFromTheClock() {
        service.create(channelRule("meteor"), ADMIN);

        ClientRuleStore.Stored stored = store.find("meteor").orElseThrow();
        assertEquals(ADMIN, stored.recordedByOid(), "verified actor");
        assertEquals(clock.millis(), stored.createdAt());
    }

    @Test
    void creatingARuleEvictsTheProxyMirrorAndNotifiesTheProxies() {
        sharedCache.put(CacheKeys.CLIENT_RULES_ALL, io.micronaut.core.type.Argument.listOf(ClientRuleDTO.Response.class), List.of(), Duration.ofMinutes(5));

        service.create(channelRule("meteor"), ADMIN);

        assertFalse(sharedCache.contains(CacheKeys.CLIENT_RULES_ALL), "the mirror is evicted");
        assertEquals(List.of("meteor"), notified, "proxies are told which rule changed");
    }

    @Test
    void aDuplicateKeyIsAConflictAndTheExistingRuleIsUnchanged() {
        service.create(channelRule("meteor"), ADMIN);
        notified.clear();

        expectError(service.create(new ClientRuleDTO.CreateRequest("meteor", "Other", ClientRuleKind.CHANNEL, List.of("other:*"),
                null, null, null, null, null, null, null, null, null, null, null, null), ADMIN), ErrorKind.CONFLICT);

        assertEquals("Meteor client", store.find("meteor").orElseThrow().spec().displayName());
        assertTrue(notified.isEmpty(), "nothing changed, nobody is told");
    }

    @Test
    void anInvalidRuleStoresNothingAndNotifiesNobody() {
        ClientRuleDTO.CreateRequest invalid = new ClientRuleDTO.CreateRequest("meteor", "Meteor", ClientRuleKind.PROTOCOL, List.of("x"),
                null, null, null, null, null, null, null, null, null, null, null, null);

        expectError(service.create(invalid, ADMIN), ErrorKind.VALIDATION);

        assertTrue(store.all().isEmpty());
        assertTrue(notified.isEmpty());
    }

    @Test
    void aSectionThatDoesNotExistOrIsNotAServerSectionIsRejected() {
        ClientRuleDTO.CreateRequest request = new ClientRuleDTO.CreateRequest("scoped", "Scoped", ClientRuleKind.CHANNEL, List.of("x:*"),
                null, null, null, ClientRuleScope.SERVERS, List.of("nope"), null, null, null, null, null, null, null);

        ClientRuleDTO.Error error = expectError(service.create(request, ADMIN), ErrorKind.VALIDATION);

        assertTrue(error.errorMessage().contains("nope"), "the message names the section: " + error.errorMessage());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void aRuleLimitedToAServerSectionIsAccepted() {
        ClientRuleDTO.CreateRequest request = new ClientRuleDTO.CreateRequest("scoped", "Scoped", ClientRuleKind.CHANNEL, List.of("x:*"),
                null, null, null, ClientRuleScope.SERVERS, List.of("minigames"), ServerTargetKind.CLOUDNET_TASK, List.of("BedWars"),
                null, null, null, null, null);

        ClientRuleDTO.Response created = assertInstanceOf(ClientRuleDTO.Response.class, service.create(request, ADMIN));

        assertEquals(List.of("minigames"), created.sectionKeys());
        assertEquals(ServerTargetKind.CLOUDNET_TASK, created.targetKind());
        assertEquals(List.of("BedWars"), created.servers());
    }

    @Test
    void aTemplateThatDoesNotExistIsRejected() {
        expectError(service.create(brandTemplateRule("brand", true), ADMIN), ErrorKind.VALIDATION);
        assertTrue(store.all().isEmpty());
    }

    @Test
    void punishingOnABrandWithoutAcknowledgementIsRejectedAndWithItAccepted() {
        store.givenTemplate("cheating");

        ClientRuleDTO.Error error = expectError(service.create(brandTemplateRule("brand", false), ADMIN), ErrorKind.VALIDATION);

        assertTrue(error.errorMessage().contains("can fake it"), error.errorMessage());
        ClientRuleDTO.Response created = assertInstanceOf(ClientRuleDTO.Response.class, service.create(brandTemplateRule("brand", true), ADMIN));
        assertTrue(created.acknowledgedSpoofable(), "stored and flagged");
    }

    @Test
    void theAcknowledgementDoesNotSurviveAChangeOfActionOrKind() {
        store.givenTemplate("cheating");
        service.create(brandTemplateRule("brand", true), ADMIN);

        ClientRuleDTO.Response notify = assertInstanceOf(ClientRuleDTO.Response.class,
                service.update("brand", update("brand", ClientRuleKind.BRAND, ClientRuleAction.NOTIFY, null, true), ADMIN));
        assertFalse(notify.acknowledgedSpoofable(), "cleared when the action changed");

        expectError(service.update("brand", update("brand", ClientRuleKind.BRAND, ClientRuleAction.TEMPLATE, "cheating", false), ADMIN), ErrorKind.VALIDATION);
        assertEquals(ClientRuleAction.NOTIFY, store.find("brand").orElseThrow().spec().action(), "the rejected update changed nothing");
    }

    @Test
    void anUpdateReplacesTheFieldsKeepsTheHitCountAndNotifies() {
        store.given(ClientRuleSpec.builder("meteor", "Old name", ClientRuleKind.CHANNEL).patterns("a:*").build());
        notified.clear();

        ClientRuleDTO.Response updated = assertInstanceOf(ClientRuleDTO.Response.class, service.update("meteor",
                new ClientRuleDTO.UpdateRequest("meteor", "New name", ClientRuleKind.CHANNEL, false, List.of("b:*"),
                        null, null, null, null, null, null, null, ClientRuleAction.DENY, "Not allowed here.", null, null, null), ADMIN));

        assertEquals("New name", updated.displayName());
        assertFalse(updated.enabled(), "enabled is part of the update");
        assertEquals(List.of("b:*"), updated.patterns());
        assertEquals("Not allowed here.", updated.denyMessage());
        assertEquals(List.of("meteor"), notified);
    }

    @Test
    void updatingAnUnknownRuleIsNotFound() {
        expectError(service.update("ghost", update("ghost", ClientRuleKind.BRAND, null, null, false), ADMIN), ErrorKind.NOT_FOUND);
    }

    @Test
    void aBodyKeyThatDiffersFromThePathKeyIsRejected() {
        service.create(channelRule("meteor"), ADMIN);

        expectError(service.update("meteor", update("other", ClientRuleKind.BRAND, null, null, false), ADMIN), ErrorKind.VALIDATION);
    }

    @Test
    void deletingARuleRemovesItEvictsTheMirrorAndNotifies() {
        service.create(channelRule("meteor"), ADMIN);
        notified.clear();

        assertInstanceOf(ClientRuleDTO.Response.class, service.delete("meteor"));

        assertTrue(store.all().isEmpty());
        assertEquals(List.of("meteor"), notified);
        expectError(service.delete("meteor"), ErrorKind.NOT_FOUND);
    }

    @Test
    void aSingleRuleIsReadByKeyAndAnUnknownKeyIsNotFound() {
        service.create(channelRule("meteor"), ADMIN);

        assertEquals("meteor", assertInstanceOf(ClientRuleDTO.Response.class, service.get("meteor")).key());
        expectError(service.get("ghost"), ErrorKind.NOT_FOUND);
    }

    @Test
    void theListIsSortedByKeyAndRefreshesTheMirrorWithEnabledRulesOnly() {
        service.create(channelRule("zeta"), ADMIN);
        service.create(channelRule("alpha"), ADMIN);
        service.update("zeta", new ClientRuleDTO.UpdateRequest("zeta", "Z", ClientRuleKind.CHANNEL, false, List.of("z:*"),
                null, null, null, null, null, null, null, null, null, null, null, null), ADMIN);

        List<ClientRuleDTO.Response> all = service.list();

        assertEquals(List.of("alpha", "zeta"), all.stream().map(ClientRuleDTO.Response::key).toList());
        List<ClientRuleDTO.Response> mirrored = sharedCache.get(CacheKeys.CLIENT_RULES_ALL,
                io.micronaut.core.type.Argument.listOf(ClientRuleDTO.Response.class)).orElseThrow();
        assertEquals(List.of("alpha"), mirrored.stream().map(ClientRuleDTO.Response::key).toList(), "proxies get the enabled rules");
    }
}
