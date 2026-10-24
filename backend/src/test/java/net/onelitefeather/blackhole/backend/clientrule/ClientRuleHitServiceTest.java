package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleHitDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.FakeClientRuleHitStore;
import net.onelitefeather.blackhole.backend.support.FakeClientRuleStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuleHitServiceTest {

    private static final String OWNER = "ab".repeat(64);

    private final FakeClientRuleStore rules = new FakeClientRuleStore();
    private final FakeClientRuleHitStore hits = new FakeClientRuleHitStore(rules);
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final TestClock clock = new TestClock();
    private final List<String> applied = new ArrayList<>();
    private ClientRuleTemplateApplier.Result applierResult = new ClientRuleTemplateApplier.Result(ClientRuleTemplateApplier.Outcome.APPLIED, "punishment-1");
    private boolean applierFails;
    private final ClientRuleTemplateApplier applier = (owner, template, rule) -> {
        applied.add(owner + "/" + template + "/" + rule);
        if (applierFails) {
            throw new IllegalStateException("backend down");
        }
        return applierResult;
    };
    private final ClientRuleHitService service = new ClientRuleHitService(hits, applier, events, rules, clock);

    private void givenChannelRule(String key, ClientRuleAction action) {
        ClientRuleSpec.Builder rule = ClientRuleSpec.builder(key, key, ClientRuleKind.CHANNEL).patterns("meteor:*").action(action);
        if (action == ClientRuleAction.TEMPLATE) {
            rule.template("cheating").acknowledged();
        }
        rules.given(rule.build());
    }

    private static ClientRuleHitDTO.CreateRequest hit(String rule, boolean enforced, boolean bypassed) {
        return new ClientRuleHitDTO.CreateRequest(rule, OWNER, "lobby-1", enforced, bypassed);
    }

    private ClientRuleHitDTO.Error expectError(ClientRuleHitDTO result, ErrorKind kind) {
        ClientRuleHitDTO.Error error = assertInstanceOf(ClientRuleHitDTO.Error.class, result, "expected an error but got " + result);
        assertEquals(kind, error.kind(), error.errorMessage());
        return error;
    }

    @Test
    void aHitIsStoredWithTheRuleOwnerTimeActionAndServerOnly() {
        givenChannelRule("meteor", ClientRuleAction.NOTIFY);

        ClientRuleHitDTO.Recorded recorded = assertInstanceOf(ClientRuleHitDTO.Recorded.class, service.record(hit("meteor", false, false)));

        assertEquals("meteor", recorded.rule());
        assertEquals(List.of(new ClientRuleHitStore.Hit("meteor", OWNER, clock.millis(), ClientRuleAction.NOTIFY, false, false, "lobby-1")), hits.all());
    }

    @Test
    void aHitIncrementsTheRulesCounter() {
        givenChannelRule("meteor", ClientRuleAction.NOTIFY);

        service.record(hit("meteor", false, false));
        service.record(hit("meteor", false, false));

        assertEquals(2, rules.find("meteor").orElseThrow().hitCount());
    }

    @Test
    void aHitOnAnUnknownRuleIsNotFoundAndStoresNothing() {
        expectError(service.record(hit("ghost", false, false)), ErrorKind.NOT_FOUND);

        assertTrue(hits.all().isEmpty());
        assertTrue(events.events().isEmpty());
    }

    @Test
    void aHitOnADisabledRuleIsNotFoundAndStoresNothing() {
        rules.given(ClientRuleSpec.builder("off", "Off", ClientRuleKind.CHANNEL).patterns("x:*").disabled().build());

        expectError(service.record(hit("off", false, false)), ErrorKind.NOT_FOUND);

        assertTrue(hits.all().isEmpty());
    }

    @Test
    void aMalformedOwnerOrServerIsRejected() {
        givenChannelRule("meteor", ClientRuleAction.NOTIFY);

        expectError(service.record(new ClientRuleHitDTO.CreateRequest("meteor", "not-a-hash", null, false, false)), ErrorKind.VALIDATION);
        expectError(service.record(new ClientRuleHitDTO.CreateRequest("meteor", OWNER, "bad name!", false, false)), ErrorKind.VALIDATION);
        assertTrue(hits.all().isEmpty());
    }

    @Test
    void theEnforcedAndBypassedFlagsAreStored() {
        givenChannelRule("meteor", ClientRuleAction.DENY);

        service.record(hit("meteor", true, false));
        service.record(hit("meteor", false, true));

        assertEquals(List.of(true, false), hits.all().stream().map(ClientRuleHitStore.Hit::enforced).toList());
        assertEquals(List.of(false, true), hits.all().stream().map(ClientRuleHitStore.Hit::bypassed).toList());
    }

    @Test
    void theHitEventCarriesTheRuleOwnerKindActionAndTimeAndNothingElse() {
        givenChannelRule("meteor", ClientRuleAction.DENY);

        service.record(hit("meteor", true, false));

        assertEquals(1, events.eventsOfType("client-rule.hit").size());
        Map<String, Object> payload = events.eventsOfType("client-rule.hit").getFirst().payload();
        assertEquals(Set.of("rule", "owner", "kind", "action", "at"), payload.keySet(), "no player name, no trait, no server");
        assertEquals(Map.of("rule", "meteor", "owner", OWNER, "kind", "CHANNEL", "action", "DENY", "at", clock.millis()), payload);
    }

    @Test
    void aTemplateRuleAppliesItsTemplateAndAnswersWithThePunishment() {
        givenChannelRule("brand", ClientRuleAction.TEMPLATE);

        ClientRuleHitDTO.Recorded recorded = assertInstanceOf(ClientRuleHitDTO.Recorded.class, service.record(hit("brand", false, false)));

        assertEquals(List.of(OWNER + "/cheating/brand"), applied);
        assertTrue(recorded.templateApplied());
        assertEquals("punishment-1", recorded.punishmentIdentifier());
    }

    @Test
    void aRepeatedHitWhileThePunishmentIsActiveAppliesNothingNew() {
        givenChannelRule("brand", ClientRuleAction.TEMPLATE);
        applierResult = new ClientRuleTemplateApplier.Result(ClientRuleTemplateApplier.Outcome.ALREADY_ACTIVE, null);

        ClientRuleHitDTO.Recorded recorded = assertInstanceOf(ClientRuleHitDTO.Recorded.class, service.record(hit("brand", false, false)));

        assertFalse(recorded.templateApplied());
        assertNull(recorded.punishmentIdentifier());
        assertEquals(1, hits.all().size(), "the hit is still recorded");
    }

    @Test
    void aBypassedPlayerIsNeverPunishedButTheHitIsRecorded() {
        givenChannelRule("brand", ClientRuleAction.TEMPLATE);

        ClientRuleHitDTO.Recorded recorded = assertInstanceOf(ClientRuleHitDTO.Recorded.class, service.record(hit("brand", false, true)));

        assertTrue(applied.isEmpty(), "no template for staff");
        assertFalse(recorded.templateApplied());
        assertEquals(1, hits.all().size());
    }

    @Test
    void notifyAndDenyRulesNeverApplyATemplate() {
        givenChannelRule("notify", ClientRuleAction.NOTIFY);
        givenChannelRule("deny", ClientRuleAction.DENY);

        service.record(hit("notify", false, false));
        service.record(hit("deny", true, false));

        assertTrue(applied.isEmpty());
    }

    @Test
    void whenTheBackendCannotApplyTheTemplateTheHitStandsAndNothingIsPunished() {
        givenChannelRule("brand", ClientRuleAction.TEMPLATE);
        applierFails = true;

        ClientRuleHitDTO.Recorded recorded = assertInstanceOf(ClientRuleHitDTO.Recorded.class, service.record(hit("brand", false, false)));

        assertFalse(recorded.templateApplied());
        assertEquals(1, hits.all().size());
    }

    @Test
    void theHitsOfARuleAreListedNewestFirstAndLimited() {
        givenChannelRule("meteor", ClientRuleAction.NOTIFY);
        for (int i = 0; i < 3; i++) {
            service.record(hit("meteor", false, false));
            clock.advance(Duration.ofMinutes(1));
        }

        ClientRuleHitDTO.Hits all = assertInstanceOf(ClientRuleHitDTO.Hits.class, service.ofRule("meteor", null));
        ClientRuleHitDTO.Hits two = assertInstanceOf(ClientRuleHitDTO.Hits.class, service.ofRule("meteor", 2));

        assertEquals(3, all.hits().size());
        assertTrue(all.hits().get(0).at().isAfter(all.hits().get(1).at()), "newest first");
        assertEquals(2, two.hits().size());
        assertEquals(OWNER, all.hits().getFirst().owner());
    }

    @Test
    void listingTheHitsOfAnUnknownRuleIsNotFound() {
        expectError(service.ofRule("ghost", null), ErrorKind.NOT_FOUND);
    }

    @Test
    void theHitsOfAPlayerAreListedAcrossRulesAndTheOwnerMustBeAHash() {
        givenChannelRule("a", ClientRuleAction.NOTIFY);
        givenChannelRule("b", ClientRuleAction.NOTIFY);
        service.record(hit("a", false, false));
        service.record(hit("b", false, false));
        service.record(new ClientRuleHitDTO.CreateRequest("a", "cd".repeat(64), null, false, false));

        assertEquals(2, assertInstanceOf(ClientRuleHitDTO.Hits.class, service.ofOwner(OWNER, null)).hits().size());
        expectError(service.ofOwner("nope", null), ErrorKind.VALIDATION);
    }
}
