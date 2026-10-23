package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.EligibilityResult;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.STAFF;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppealEligibilityServiceTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final AppealEligibilityService service = new AppealEligibilityService(
            appeals.repository(), bed.punishments.repository(), bed.elo, bed.eloBed.sections, bed.cases.timeline, bed.eloBed.clock, 14, 3, 7);

    @Test
    void aPermanentNetworkBanIsTheSevereTier() {
        bed.elo.applyDelta(OWNER, "network", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());
        PunishmentEntity ban = bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("network");

        EligibilityResult result = service.evaluate(ban, OWNER);

        assertTrue(result.severe(), "only an appeal against a permanent BAN may be limited to a duration reduction");
        assertEquals("SEVERE", result.checklistSnapshot().get("severityTier"));
    }

    @Test
    void aPermanentMuteIsNotSevere() {
        bed.elo.applyDelta(OWNER, "chat", -800, EloReasonCode.MANUAL_ADJUSTMENT, null, Map.of());
        PunishmentEntity mute = bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("chat");

        assertFalse(service.evaluate(mute, OWNER).severe());
    }

    @Test
    void aTemporaryBanIsNotSevere() {
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "grief", null, null), ACTOR);

        assertFalse(service.evaluate(bed.punishments.all().getFirst(), OWNER).severe());
    }

    @Test
    void theSupportingSectionsAreEveryOtherEnabledSectionAndMustHaveRecoveredTheirBaseScore() {
        bed.givenTemplate("grief", "network", null, step("P3D", 0));
        bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "grief", null, null), ACTOR);
        bed.eloBed.givenScore(OWNER, "chat", 800);

        Map<String, Object> checklist = service.evaluate(bed.punishments.all().getFirst(), OWNER).checklistSnapshot();

        assertEquals(List.of("chat"), checklist.get("supportingEloSections"), "the punishment's own section is not a supporting signal");
        assertEquals(800, checklist.get("supportingEloScore"));
        assertEquals(false, checklist.get("supportingEloRecovered"), "chat is below its base score of 1000");
        assertEquals(AppealEligibilityService.CHECKLIST_VERSION, checklist.get("checklistVersion"));
    }

    @Test
    void anAutomaticPunishmentNeedsTheShorterWaitThanAManualOne() {
        bed.elo.applyDelta(OWNER, "chat", -400, EloReasonCode.TOXICITY_FLAG, null, Map.of());
        PunishmentEntity soft = bed.profiles.repository().findById(OWNER).orElseThrow().getActive().get("chat");
        bed.eloBed.clock.advance(Duration.ofDays(4));

        EligibilityResult result = service.evaluate(soft, OWNER);

        assertTrue(result.eligible(), "automatic punishments need 3 days, 4 have passed");
        assertEquals(3, result.checklistSnapshot().get("minDaysRequired"));
    }
}
