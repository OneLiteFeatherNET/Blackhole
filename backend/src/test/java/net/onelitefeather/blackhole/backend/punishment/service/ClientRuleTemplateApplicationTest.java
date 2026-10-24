package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.clientrule.ClientRuleTemplateApplier;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a TEMPLATE client rule does to a player: the ELO automatic path, once per active punishment. */
class ClientRuleTemplateApplicationTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private ClientRuleTemplateApplier.Result hit(String rule) {
        return bed.punishmentService.applyForRule(OWNER, "cheating", rule);
    }

    private PunishmentProfileEntity profile() {
        return bed.profiles.repository().findById(OWNER).orElseThrow();
    }

    private void givenLadder() {
        bed.givenTemplate("cheating", "network", null, step("P1D", 100), step("P7D", 150));
    }

    @Test
    void theFirstHitCreatesAnAutomaticPunishmentFromTheTemplatesFirstStepWithItsCost() {
        givenLadder();

        ClientRuleTemplateApplier.Result result = hit("meteor");

        assertEquals(ClientRuleTemplateApplier.Outcome.APPLIED, result.outcome());
        PunishmentEntity punishment = profile().getActive().get("network");
        assertEquals(result.punishmentIdentifier(), punishment.getIdentifier());
        assertEquals(EloService.SYSTEM_ELO_SOURCE, punishment.getSource(), "no human actor: the ELO system is the source, so appeals treat it as automatic");
        assertEquals(true, punishment.getMetaData().get("automatic"));
        assertEquals("meteor", punishment.getMetaData().get("clientRule"));
        assertEquals("client-rule:meteor", punishment.getMetaData().get("automaticSource"));
        assertEquals(0, punishment.getStepIndex(), "step 1");
        assertEquals(100, punishment.getEloCost());
        assertEquals(900, bed.eloBed.storedScore(OWNER, "network"), "the template's ELO cost is charged");
        assertNull(punishment.getRecordedByOid(), "no verified person");
    }

    @Test
    void aRepeatedHitWhileThePunishmentIsActiveAddsNothingAndDoesNotEscalate() {
        givenLadder();
        String first = hit("meteor").punishmentIdentifier();

        ClientRuleTemplateApplier.Result second = hit("meteor");

        assertEquals(ClientRuleTemplateApplier.Outcome.ALREADY_ACTIVE, second.outcome());
        assertNull(second.punishmentIdentifier());
        assertEquals(first, profile().getActive().get("network").getIdentifier(), "still the first punishment");
        assertEquals(1, bed.punishments.all().size(), "no second punishment");
        assertEquals(900, bed.eloBed.storedScore(OWNER, "network"), "charged once");
    }

    @Test
    void onceThePunishmentExpiredTheNextHitAppliesTheNextStep() {
        givenLadder();
        hit("meteor");
        bed.eloBed.clock.advance(Duration.ofDays(2));

        ClientRuleTemplateApplier.Result again = hit("meteor");

        assertEquals(ClientRuleTemplateApplier.Outcome.APPLIED, again.outcome());
        assertEquals(1, profile().getActive().get("network").getStepIndex(), "step 2 now");
    }

    @Test
    void aDifferentRulesActivePunishmentDoesNotBlockThisRule() {
        givenLadder();
        hit("meteor");

        assertEquals(ClientRuleTemplateApplier.Outcome.APPLIED, hit("other-rule").outcome());
    }

    @Test
    void anUnknownTemplateAppliesNothing() {
        ClientRuleTemplateApplier.Result result = bed.punishmentService.applyForRule(OWNER, "no-such-template", "meteor");

        assertEquals(ClientRuleTemplateApplier.Outcome.NOT_APPLIED, result.outcome());
        assertTrue(bed.punishments.all().isEmpty());
    }

    @Test
    void aPermanentPunishmentInTheSectionIsNotReplaced() {
        givenLadder();
        bed.punishmentService.applyPermanent(OWNER, bed.eloBed.sections.find("network").orElseThrow(), java.util.Map.of());

        ClientRuleTemplateApplier.Result result = hit("meteor");

        assertEquals(ClientRuleTemplateApplier.Outcome.NOT_APPLIED, result.outcome());
        assertNotNull(profile().getActive().get("network"));
        assertTrue(profile().getActive().get("network").isPermanent(), "the permanent punishment stands");
    }

    @Test
    void theCreatedEventIsMarkedAutomatic() {
        givenLadder();

        hit("meteor");

        assertEquals(true, bed.eloBed.events.eventsOfType("punishment.created").getFirst().payload().get("automatic"));
    }
}
