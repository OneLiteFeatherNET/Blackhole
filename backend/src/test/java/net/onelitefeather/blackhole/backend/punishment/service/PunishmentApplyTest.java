package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentApplyTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private PunishmentDTO.ApplyResponse apply(String templateKey) {
        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, templateKey, null, null), ACTOR);
        return assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "expected the template to be applied but got " + result);
    }

    private PunishmentDTO.Error applyExpectingError(PunishmentDTO.ApplyRequest request, ErrorKind kind) {
        PunishmentDTO.Error error = assertInstanceOf(PunishmentDTO.Error.class, bed.punishmentService.apply(request, ACTOR));
        assertEquals(kind, error.kind(), error.errorMessage());
        return error;
    }

    private PunishmentProfileEntity profile() {
        return bed.profiles.repository().findById(OWNER).orElseThrow();
    }

    private void givenHackingLadder() {
        bed.givenTemplate("hacking", "network", null, step("P1D", 100), step("P7D", 150), step("P30D", 250));
    }

    // ---- step selection (3.2) -------------------------------------------------------------

    @Test
    void aFirstOffenceUsesTheFirstStep() {
        givenHackingLadder();

        PunishmentDTO.ApplyResponse result = apply("hacking");

        assertEquals(1, result.step(), "step 1 of 3");
        assertEquals(3, result.stepCount());
        assertEquals(bed.eloBed.clock.millis() + Duration.ofDays(1).toMillis(),
                result.punishment().metaData().get("expiration_date"), "expires one day after application");
    }

    @Test
    void aSecondOffenceUsesTheSecondStepEvenWhenTheFirstPunishmentAlreadyExpired() {
        givenHackingLadder();
        apply("hacking");
        bed.eloBed.clock.advance(Duration.ofDays(30));

        PunishmentDTO.ApplyResponse second = apply("hacking");

        assertEquals(2, second.step(), "the expired first punishment still counts");
        assertEquals(bed.eloBed.clock.millis() + Duration.ofDays(7).toMillis(), second.punishment().metaData().get("expiration_date"));
    }

    @Test
    void beyondTheLastStepTheLastStepRepeats() {
        givenHackingLadder();
        apply("hacking");
        apply("hacking");
        apply("hacking");

        PunishmentDTO.ApplyResponse fourth = apply("hacking");

        assertEquals(3, fourth.step(), "clamped to the last step");
        assertEquals(bed.eloBed.clock.millis() + Duration.ofDays(30).toMillis(), fourth.punishment().metaData().get("expiration_date"));
    }

    @Test
    void offencesOlderThanTheEscalationWindowDoNotCount() {
        givenHackingLadder();
        apply("hacking");
        bed.eloBed.clock.advance(Duration.ofDays(366));

        assertEquals(1, apply("hacking").step(), "the only earlier offence is outside the default window of 365 days");
    }

    @Test
    void aTemplatesOwnWindowOverridesTheDefault() {
        PunishmentTemplateEntity template = new PunishmentTemplateEntity(UUID.randomUUID(), "spam", "Spam", "chat", "spam", "P7D",
                java.util.List.of(step("PT1H", 0), step("PT6H", 0)), new java.util.HashMap<>());
        bed.templates.given(template, template.getIdentifier());
        apply("spam");
        bed.eloBed.clock.advance(Duration.ofDays(8));

        assertEquals(1, apply("spam").step(), "8 days later the 7 day window no longer holds the first offence");
    }

    @Test
    void aRevokedPunishmentDoesNotCountTowardsTheNextStep() {
        givenHackingLadder();
        apply("hacking");
        bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "network", null), ACTOR);

        assertEquals(1, apply("hacking").step(), "the revoked punishment is not counted");
    }

    @Test
    void aPunishmentLiftedOnAppealDoesNotCountTowardsTheNextStep() {
        givenHackingLadder();
        apply("hacking");
        PunishmentEntity first = bed.punishments.all().getFirst();
        first.setLifted(true);

        assertEquals(1, apply("hacking").step(), "lifted punishments are excluded from step selection");
    }

    @Test
    void templatesSharingAnEscalationGroupCountTowardsEachOther() {
        bed.givenTemplate("killaura", "network", "hacking", step("P1D", 100), step("P7D", 150));
        bed.givenTemplate("fly", "network", "hacking", step("P2D", 50), step("P14D", 75));
        apply("killaura");

        PunishmentDTO.ApplyResponse fly = apply("fly");

        assertEquals(2, fly.step(), "fly escalates because a killaura punishment is in the same group");
        assertEquals(bed.eloBed.clock.millis() + Duration.ofDays(14).toMillis(), fly.punishment().metaData().get("expiration_date"),
                "each template uses its own steps");
    }

    @Test
    void templatesInDifferentGroupsDoNotEscalateEachOther() {
        bed.givenTemplate("killaura", "network", null, step("P1D", 0), step("P7D", 0));
        bed.givenTemplate("fly", "network", null, step("P2D", 0), step("P14D", 0));
        apply("killaura");

        assertEquals(1, apply("fly").step());
    }

    @Test
    void theOffencesOfOtherPlayersDoNotCount() {
        givenHackingLadder();
        String other = "cd".repeat(64);
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(other, "hacking", null, null), ACTOR);

        assertEquals(1, apply("hacking").step());
    }

    @Test
    void thePunishmentRecordsGroupStepCountAndCostSoItStaysExplainableAfterTheTemplateChanges() {
        givenHackingLadder();
        PunishmentDTO.ApplyResponse result = apply("hacking");

        bed.templates.all().clear();

        PunishmentEntity stored = bed.punishments.all().getFirst();
        assertEquals("hacking", stored.getEscalationGroup());
        assertEquals(0, stored.getStepIndex());
        assertEquals(3, stored.getStepCount());
        assertEquals(100, stored.getEloCost());
        assertEquals("hacking", result.punishment().escalationGroup());
        assertEquals(100, result.punishment().eloCost());
    }

    // ---- apply semantics (3.4) ------------------------------------------------------------

    @Test
    void applyingToAFirstTimeOffenderCreatesTheProfileAndTheActiveSlot() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        PunishmentDTO.ApplyResponse result = apply("spam");

        assertEquals("chat", result.section());
        assertEquals(EnforcementKind.MUTE, result.enforcement());
        assertEquals(1000, result.scoreBefore());
        assertEquals(950, result.scoreAfter());
        assertNull(result.escalation());
        assertEquals(result.punishment().identifier(), profile().getActive().get("chat").getIdentifier());
        assertEquals(1, bed.eloBed.events.eventsOfType("profile.created").size(), "profile.created");
        assertEquals(1, bed.eloBed.events.eventsOfType("punishment.created").size(), "punishment.created");
    }

    @Test
    void applyingByIdWorksLikeApplyingByKey() {
        PunishmentTemplateEntity template = bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        PunishmentDTO result = bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, null, template.getIdentifier(), null), ACTOR);

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, result);
    }

    @Test
    void anUnknownTemplateIsNotFoundAndChangesNothing() {
        applyExpectingError(new PunishmentDTO.ApplyRequest(OWNER, "ghost", null, null), ErrorKind.NOT_FOUND);

        assertTrue(bed.punishments.all().isEmpty(), "no punishment");
        assertTrue(bed.profiles.all().isEmpty(), "no profile");
        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "no deduction");
    }

    @Test
    void aRequestNamingNoTemplateOrBothIsRejected() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        applyExpectingError(new PunishmentDTO.ApplyRequest(OWNER, null, null, null), ErrorKind.VALIDATION);
        applyExpectingError(new PunishmentDTO.ApplyRequest(OWNER, "spam", UUID.randomUUID(), null), ErrorKind.VALIDATION);
    }

    @Test
    void anOwnerThatIsNotAHashIsRejectedWithoutAnyChange() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50));

        applyExpectingError(new PunishmentDTO.ApplyRequest("not-a-hash", "spam", null, null), ErrorKind.VALIDATION);

        assertTrue(bed.punishments.all().isEmpty());
    }

    @Test
    void aTemplateOfADisabledSectionIsRejectedWithoutPunishmentOrDeduction() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        bed.eloBed.sections.disable("chat");

        applyExpectingError(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ErrorKind.CONFLICT);

        assertTrue(bed.punishments.all().isEmpty(), "no punishment");
        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "no deduction");
    }

    @Test
    void aStaffPunishmentDeductsItsCostFromTheSectionAndRecordsWhy() {
        bed.givenTemplate("grief", "network", null, step("P3D", 200));

        PunishmentDTO.ApplyResponse result = apply("grief");

        assertEquals(800, result.scoreAfter());
        assertEquals(800, bed.eloBed.storedScore(OWNER, "network"));
        var event = bed.eloBed.eventRepository.all().getFirst();
        assertEquals(EloReasonCode.PUNISHMENT_APPLIED, event.getReasonCode());
        assertEquals(result.punishment().identifier(), event.getMetaData().get("punishmentIdentifier"));
    }

    @Test
    void aCostOfZeroCreatesNoEloEventButStillReportsTheScore() {
        bed.givenTemplate("warn", "chat", null, step("PT1H", 0));
        bed.eloBed.givenScore(OWNER, "chat", 640);

        PunishmentDTO.ApplyResponse result = apply("warn");

        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "no ELO event for a free punishment");
        assertEquals(640, result.scoreBefore());
        assertEquals(640, result.scoreAfter());
    }

    @Test
    void aPunishmentFromTheEloSystemSourceDeductsNothing() {
        bed.givenTemplate("grief", "network", null, step("P3D", 200));

        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "grief", null,
                net.onelitefeather.blackhole.backend.elo.service.EloService.SYSTEM_ELO_SOURCE), ACTOR);

        assertTrue(bed.eloBed.eventRepository.all().isEmpty(), "the ELO system never charges itself");
    }

    @Test
    void aDeductionBelowThePermanentThresholdReplacesTheStaffPunishmentWithAPermanentOne() {
        bed.givenTemplate("grief", "network", null, step("P3D", 150));
        bed.eloBed.givenScore(OWNER, "network", 380);

        PunishmentDTO.ApplyResponse result = apply("grief");

        assertEquals(230, result.scoreAfter());
        assertNotNull(result.escalation(), "the apply result reports the escalation");
        PunishmentDTO.Escalation escalation = result.escalation();
        assertTrue(escalation.punishment().permanent(), "the escalation is permanent");
        assertNull(escalation.punishment().template(), "permanent punishments have no template");
        PunishmentProfileEntity profile = profile();
        assertEquals(escalation.punishment().identifier(), profile.getActive().get("network").getIdentifier(), "permanent ban is the active network punishment");
        assertEquals(1, profile.getHistory().size(), "the staff temp ban moved to history");
        assertEquals(result.punishment().identifier(), profile.getHistory().getFirst().getIdentifier());
        assertEquals(1, bed.eloBed.events.eventsOfType("section.escalated").size(), "section.escalated is published");
    }

    @Test
    void aStaffDeductionThatCrossesTheSoftThresholdDoesNotAddAnAutomaticPunishmentOnTop() {
        bed.givenTemplate("grief", "network", null, step("P3D", 100));
        bed.eloBed.givenScore(OWNER, "network", 750);

        PunishmentDTO.ApplyResponse result = apply("grief");

        assertEquals(650, result.scoreAfter());
        assertEquals(result.punishment().identifier(), profile().getActive().get("network").getIdentifier(),
                "the staff punishment stays the active one");
        assertTrue(profile().getHistory().isEmpty(), "nothing was replaced");
    }

    @Test
    void applyingToAnOwnerWhoIsAlreadyPermanentlyPunishedInTheSectionIsAConflict() {
        bed.givenTemplate("grief", "network", null, step("P3D", 150));
        bed.eloBed.givenScore(OWNER, "network", 380);
        apply("grief");
        int punishmentsBefore = bed.punishments.all().size();

        applyExpectingError(new PunishmentDTO.ApplyRequest(OWNER, "grief", null, null), ErrorKind.CONFLICT);

        assertEquals(punishmentsBefore, bed.punishments.all().size(), "the permanent punishment is not downgraded");
    }

    // ---- active slots per section (3.3) ---------------------------------------------------

    @Test
    void aMuteDoesNotLiftAnActiveBan() {
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        PunishmentDTO.ApplyResponse ban = apply("grief");

        PunishmentDTO.ApplyResponse mute = apply("spam");

        PunishmentProfileEntity profile = profile();
        assertEquals(ban.punishment().identifier(), profile.getActive().get("network").getIdentifier(), "the ban is still active");
        assertEquals(mute.punishment().identifier(), profile.getActive().get("chat").getIdentifier(), "the mute occupies the chat slot");
        assertTrue(profile.getHistory().isEmpty(), "nothing moved to history");
    }

    @Test
    void aNewPunishmentInTheSameSectionMovesTheActiveOneToHistory() {
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        PunishmentDTO.ApplyResponse first = apply("grief");

        PunishmentDTO.ApplyResponse second = apply("grief");

        PunishmentProfileEntity profile = profile();
        assertEquals(second.punishment().identifier(), profile.getActive().get("network").getIdentifier());
        assertEquals(first.punishment().identifier(), profile.getHistory().getFirst().getIdentifier(), "the replaced ban is kept in history");
    }

    @Test
    void applyingEvictsTheCachedProfile() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));
        bed.profileCache.put(OWNER, new net.onelitefeather.blackhole.backend.profile.dto.PunishProfileDTO(OWNER, Map.of(), java.util.List.of(), Map.of()));

        apply("spam");

        assertFalse(bed.eloBed.sharedCache.contains(CacheKeys.profile(OWNER)));
    }

    @Test
    void thePunishmentCreatedEventCarriesSectionEnforcementAndExpiry() {
        bed.givenTemplate("spam", "chat", null, step("PT1H", 0));

        apply("spam");

        RecordingEventPublisher.Event created = bed.eloBed.events.eventsOfType("punishment.created").getFirst();
        assertEquals("chat", created.payload().get("section"));
        assertEquals("MUTE", created.payload().get("enforcement"));
        assertEquals(OWNER, created.payload().get("owner"));
        assertEquals("reason of spam", created.payload().get("reason"));
        assertEquals(bed.eloBed.clock.millis() + Duration.ofHours(1).toMillis(), created.payload().get("expiresAt"));
    }

    // ---- automatic punishments from the ELO system ----------------------------------------

    @Test
    void anAutomaticPermanentPunishmentHasNoTemplateAndCarriesTheSectionsReason() {
        bed.elo.applyDelta(OWNER, "chat", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());

        PunishmentEntity permanent = profile().getActive().get("chat");
        assertTrue(permanent.isPermanent());
        assertNull(permanent.getTemplate(), "no template");
        assertEquals("permanent chat", permanent.getMetaData().get("reason"));
        assertEquals(net.onelitefeather.blackhole.backend.elo.service.EloService.SYSTEM_ELO_SOURCE, permanent.getSource());
        assertFalse(permanent.getMetaData().containsKey("expiration_date"), "never expires");
    }

    @Test
    void anAutomaticSoftPunishmentLastsTheSectionsSoftDuration() {
        bed.elo.applyDelta(OWNER, "chat", -400, EloReasonCode.TOXICITY_FLAG, null, Map.of());

        PunishmentEntity soft = profile().getActive().get("chat");
        assertFalse(soft.isPermanent(), "temporary");
        assertEquals(bed.eloBed.clock.millis() + Duration.ofHours(24).toMillis(), soft.getMetaData().get("expiration_date"));
    }

    @Test
    void anAutomaticPunishmentInOneSectionLeavesTheOtherSectionsUntouched() {
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        PunishmentDTO.ApplyResponse ban = apply("grief");

        bed.elo.applyDelta(OWNER, "chat", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());

        assertEquals(ban.punishment().identifier(), profile().getActive().get("network").getIdentifier(), "chat escalation did not touch the network ban");
        assertEquals(-1, bed.eloBed.storedScore(OWNER, "network"), "the network score was never touched");
    }
}
