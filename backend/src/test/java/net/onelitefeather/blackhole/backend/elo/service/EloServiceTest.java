package net.onelitefeather.blackhole.backend.elo.service;

import net.onelitefeather.blackhole.backend.cache.CacheKeys;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloEventEntity;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EscalationPolicy;
import net.onelitefeather.blackhole.backend.elo.dto.EloDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.support.EloTestBed;
import net.onelitefeather.blackhole.backend.support.RecordingSectionPunisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloServiceTest {

    private static final String OWNER = "ab".repeat(64);

    private final EloTestBed bed = new EloTestBed();

    private EloChange.Applied applied(EloChange change) {
        return assertInstanceOf(EloChange.Applied.class, change, "expected the delta to be applied but got " + change);
    }

    private EloChange delta(String section, int delta) {
        return bed.elo.applyDelta(OWNER, section, delta, EloReasonCode.TOXICITY_FLAG, null, Map.of());
    }

    @Test
    void aDeltaInOneSectionLeavesTheOtherSectionUntouched() {
        bed.givenScore(OWNER, "network", 900);

        delta("chat", -50);

        assertEquals(950, bed.storedScore(OWNER, "chat"), "chat changed by the delta");
        assertEquals(900, bed.storedScore(OWNER, "network"), "network is independent");
    }

    @Test
    void anUnscoredSectionStartsFromItsBaseScore() {
        EloChange.Applied result = applied(delta("network", -100));

        assertEquals(1000, result.scoreBefore(), "base score before");
        assertEquals(900, result.scoreAfter(), "base score minus the delta");
        assertEquals(900, bed.storedScore(OWNER, "network"));
    }

    @Test
    void anUnscoredSectionReadsAsItsBaseScore() {
        assertEquals(Optional.of(1000), bed.elo.score(OWNER, "network"));
    }

    @Test
    void theScoreOfAnUnknownSectionIsEmpty() {
        assertEquals(Optional.empty(), bed.elo.score(OWNER, "ghost"));
    }

    @Test
    void theProfileListsEveryEnabledSectionWithBaseScoresForUnscoredOnes() {
        bed.givenScore(OWNER, "chat", 640);

        EloDTO.ProfileResponse profile = bed.elo.getProfile(OWNER);

        assertEquals(2, profile.sections().size(), "chat and network");
        EloDTO.SectionScore chat = profile.sections().stream().filter(s -> s.section().equals("chat")).findFirst().orElseThrow();
        EloDTO.SectionScore network = profile.sections().stream().filter(s -> s.section().equals("network")).findFirst().orElseThrow();
        assertEquals(640, chat.score());
        assertEquals(1000, network.score(), "never scored: base score");
        assertEquals(300, network.permanentThreshold(), "thresholds are reported with the score");
    }

    @Test
    void theProfileLeavesOutDisabledSections() {
        bed.sections.disable("chat");

        EloDTO.ProfileResponse profile = bed.elo.getProfile(OWNER);

        assertEquals(List.of("network"), profile.sections().stream().map(EloDTO.SectionScore::section).toList());
    }

    @Test
    void aDeltaRecordsAnAuditEventWithTheSectionAndTheResultingScore() {
        bed.elo.applyDelta(OWNER, "chat", -50, EloReasonCode.TOXICITY_FLAG, null, Map.of("score", 0.5));

        List<EloEventEntity> events = bed.eventRepository.all();
        assertEquals(1, events.size(), "one audit event");
        assertEquals("chat", events.getFirst().getSectionKey());
        assertEquals(-50, events.getFirst().getDelta());
        assertEquals(950, events.getFirst().getResultingScore());
        assertEquals(EloReasonCode.TOXICITY_FLAG, events.getFirst().getReasonCode());
        assertEquals(0.5, events.getFirst().getMetaData().get("score"));
    }

    @Test
    void aDeltaEvictsTheCachedProfile() {
        bed.elo.getProfile(OWNER);
        assertTrue(bed.sharedCache.contains(CacheKeys.elo(OWNER)), "profile cached after a read");

        delta("chat", -50);

        assertFalse(bed.sharedCache.contains(CacheKeys.elo(OWNER)), "a write must evict the cached standing");
    }

    @Test
    void aDeltaInAnUnknownSectionIsRejectedAndChangesNothing() {
        EloChange change = delta("ghost", -50);

        EloChange.Rejected rejected = assertInstanceOf(EloChange.Rejected.class, change);
        assertEquals(ErrorKind.NOT_FOUND, rejected.kind());
        assertTrue(bed.eventRepository.all().isEmpty(), "no audit event");
        assertTrue(bed.scoreRepository.all().isEmpty(), "no score row");
    }

    @Test
    void aDeltaInADisabledSectionIsRejectedAndChangesNothing() {
        bed.sections.disable("chat");

        EloChange change = delta("chat", -50);

        EloChange.Rejected rejected = assertInstanceOf(EloChange.Rejected.class, change);
        assertEquals(ErrorKind.CONFLICT, rejected.kind());
        assertTrue(bed.scoreRepository.all().isEmpty(), "no score row");
        assertTrue(bed.punisher.calls().isEmpty(), "no automatic punishment");
    }

    @Test
    void pendingDecayIsAppliedBeforeTheDelta() {
        bed.givenScore(OWNER, "chat", 800);
        bed.clock.advance(Duration.ofDays(3));

        EloChange.Applied result = applied(delta("chat", -50));

        assertEquals(830, result.scoreBefore(), "three days at 10 per day recovered before the delta");
        assertEquals(780, result.scoreAfter());
        assertTrue(bed.eventRepository.all().stream().anyMatch(e -> e.getReasonCode() == EloReasonCode.DECAY_RECOVERY && e.getDelta() == 30),
                "the recovery is part of the audit trail");
    }

    @Test
    void decayNeverOvershootsTheBaseScore() {
        bed.givenScore(OWNER, "chat", 990);
        bed.clock.advance(Duration.ofDays(30));

        EloChange.Applied result = applied(delta("chat", 0));

        assertEquals(1000, result.scoreBefore(), "recovery stops at the base score");
    }

    @Test
    void lessThanADayOfDecayChangesNothing() {
        bed.givenScore(OWNER, "chat", 800);
        bed.clock.advance(Duration.ofHours(23));

        EloChange.Applied result = applied(delta("chat", -10));

        assertEquals(800, result.scoreBefore());
    }

    @Test
    void theNightlySweepRecoversAStoredScoreWithoutAnyDelta() {
        bed.givenScore(OWNER, "chat", 800);
        bed.clock.advance(Duration.ofDays(2));

        boolean changed = bed.elo.reconcileDecayForScore(bed.scoreRepository.all().getFirst());

        assertTrue(changed, "decay was owed");
        assertEquals(820, bed.storedScore(OWNER, "chat"));
    }

    @Test
    void aPardonResetSetsTheScoreToThePardonScoreAndRecordsIt() {
        bed.givenScore(OWNER, "network", 150);

        Optional<Integer> score = bed.elo.resetToPardon(OWNER, "network", Map.of());

        assertEquals(Optional.of(700), score);
        assertEquals(700, bed.storedScore(OWNER, "network"));
        EloEventEntity event = bed.eventRepository.all().getFirst();
        assertEquals(EloReasonCode.PARDON_RESET, event.getReasonCode());
        assertEquals(550, event.getDelta(), "the event carries the difference");
    }

    @Test
    void aPardonResetInAnUnknownSectionDoesNothing() {
        assertEquals(Optional.empty(), bed.elo.resetToPardon(OWNER, "ghost", Map.of()));
    }

    @Test
    void crossingTheSoftThresholdAppliesASoftPunishmentForTheSoftDuration() {
        bed.givenScore(OWNER, "chat", 720);

        EloChange.Applied result = applied(delta("chat", -40));

        assertEquals(1, bed.punisher.calls().size(), "one automatic punishment");
        RecordingSectionPunisher.Call call = bed.punisher.calls().getFirst();
        assertEquals("SOFT", call.kind());
        assertEquals(Duration.ofHours(24), call.duration(), "the chat section's soft duration");
        assertEquals("TOXICITY_FLAG", call.metaData().get("eloTriggerReasonCode"), "the trigger is recorded for the appeal checklist");
        assertEquals(EloChange.Tier.SOFT, result.automatic().orElseThrow().tier());
    }

    @Test
    void fallingBelowThePermanentThresholdEscalatesAndPublishesTheEscalation() {
        bed.givenScore(OWNER, "network", 380);

        EloChange.Applied result = applied(delta("network", -150));

        assertEquals(230, result.scoreAfter());
        assertEquals(List.of("PERMANENT"), bed.punisher.calls().stream().map(RecordingSectionPunisher.Call::kind).toList());
        List<Map<String, Object>> escalations = bed.events.eventsOfType("section.escalated").stream().map(e -> e.payload()).toList();
        assertEquals(1, escalations.size(), "section.escalated is published once");
        assertEquals("network", escalations.getFirst().get("section"));
        assertEquals(230, escalations.getFirst().get("resultingScore"));
        assertEquals(OWNER, escalations.getFirst().get("owner"));
        assertEquals(result.automatic().orElseThrow().punishmentIdentifier(), escalations.getFirst().get("punishmentIdentifier"));
    }

    @Test
    void aPlayerAlreadyPermanentlyPunishedIsNotEscalatedAgain() {
        bed.givenScore(OWNER, "chat", 250);
        bed.punisher.givenActivePermanent(OWNER, "chat");

        EloChange.Applied result = applied(delta("chat", -20));

        assertTrue(bed.punisher.calls().isEmpty(), "no additional permanent punishment");
        assertEquals(Optional.empty(), result.automatic());
    }

    @Test
    void aPardonedPlayerBelowTheThresholdIsEscalatedAgainOnTheNextDeduction() {
        bed.givenScore(OWNER, "network", 250);

        delta("network", -20);

        assertEquals(List.of("PERMANENT"), bed.punisher.calls().stream().map(RecordingSectionPunisher.Call::kind).toList());
    }

    @Test
    void aRewardNeverAsksThePunisherAnything() {
        bed.givenScore(OWNER, "chat", 250);

        applied(bed.elo.applyDelta(OWNER, "chat", 50, EloReasonCode.REPORT_REWARDED, null, Map.of()));

        assertTrue(bed.punisher.calls().isEmpty());
    }

    @Test
    void aSectionWithoutSoftThresholdAppliesNoSoftPunishment() {
        bed.sections.update(new SectionDTO.UpdateRequest(
                "chat", "Chat", net.onelitefeather.blackhole.backend.section.EnforcementKind.MUTE, null,
                1000, null, null, 300, 700, "muted", 10, true));

        applied(delta("chat", -400));

        assertTrue(bed.punisher.calls().isEmpty(), "700 is no longer a threshold in this section");
    }

    @Test
    void aSwappedEscalationPolicyDecidesInsteadOfTheThresholds() {
        EscalationPolicy always = context -> EscalationPolicy.Decision.PERMANENT;
        EloTestBed custom = new EloTestBed(always);

        custom.elo.applyDelta(OWNER, "chat", -1, EloReasonCode.TOXICITY_FLAG, null, Map.of());

        assertEquals(1, custom.punisher.calls().size(), "the custom policy is honoured");
    }

    @Test
    void anAdjustmentRecordsTheNoteAndSourceAndEscalates() {
        bed.givenScore(OWNER, "network", 600);
        UUID source = UUID.randomUUID();

        EloDTO result = bed.elo.adjust(OWNER, new EloDTO.AdjustRequest("network", -400, "evidence reviewed", source));

        EloDTO.AdjustResponse response = assertInstanceOf(EloDTO.AdjustResponse.class, result, "adjustment applied: " + result);
        assertEquals(200, response.scoreAfter());
        assertEquals("PERMANENT", response.escalation().tier());
        EloEventEntity event = bed.eventRepository.all().getFirst();
        assertEquals(EloReasonCode.MANUAL_ADJUSTMENT, event.getReasonCode());
        assertEquals("evidence reviewed", event.getMetaData().get("note"));
        assertEquals(source.toString(), event.getMetaData().get("source"));
    }

    @Test
    void anAdjustmentOfZeroIsRejected() {
        EloDTO result = bed.elo.adjust(OWNER, new EloDTO.AdjustRequest("network", 0, null, UUID.randomUUID()));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(EloDTO.Error.class, result).kind());
    }

    @Test
    void anAdjustmentInAnUnknownSectionChangesNothing() {
        EloDTO result = bed.elo.adjust(OWNER, new EloDTO.AdjustRequest("ghost", -50, null, UUID.randomUUID()));

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(EloDTO.Error.class, result).kind());
        assertTrue(bed.scoreRepository.all().isEmpty());
    }
}
