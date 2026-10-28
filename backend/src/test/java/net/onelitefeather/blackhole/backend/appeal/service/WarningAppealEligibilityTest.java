package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.EligibilityResult;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentKind;
import net.onelitefeather.blackhole.backend.punishment.StepKind;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A warning is appealable like a manual punishment: the minimum wait applies and its severity is STANDARD. */
class WarningAppealEligibilityTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();
    private final InMemoryRepository<AppealRepository, AppealEntity, java.util.UUID> appeals =
            InMemoryRepository.<AppealRepository, AppealEntity, java.util.UUID>of(AppealRepository.class, AppealEntity::getIdentifier).build();
    private final AppealEligibilityService service = new AppealEligibilityService(
            appeals.repository(), bed.punishments.repository(), bed.elo, bed.eloBed.sections, bed.cases.timeline, bed.eloBed.clock, 14, 3, 7);

    private PunishmentEntity givenWarning() {
        bed.givenTemplate("warn-chat", "chat", null, new TemplateStep(StepKind.WARN, null, 5));
        PunishmentDTO.ApplyResponse applied = (PunishmentDTO.ApplyResponse) bed.punishmentService.apply(
                new PunishmentDTO.ApplyRequest(OWNER, "warn-chat", null, null), ACTOR);
        PunishmentEntity warning = bed.punishments.all().stream()
                .filter(p -> p.getIdentifier().equals(applied.punishment().identifier())).findFirst().orElseThrow();
        assertEquals(PunishmentKind.WARN, warning.getKind(), "precondition: the template step is a warning");
        return warning;
    }

    @Test
    void aWarningIsAppealableAfterTheManualWait() {
        PunishmentEntity warning = givenWarning();
        bed.eloBed.clock.advance(Duration.ofDays(15));

        EligibilityResult result = service.evaluate(warning, OWNER);

        assertTrue(result.eligible(), "a warning older than min-days-manual may be appealed");
    }

    @Test
    void anAppealOfAWarningBeforeTheManualWaitIsIneligible() {
        PunishmentEntity warning = givenWarning();
        bed.eloBed.clock.advance(Duration.ofDays(13));

        EligibilityResult result = service.evaluate(warning, OWNER);

        assertFalse(result.eligible(), "13 days is inside the 14 day manual wait");
        assertEquals(false, result.checklistSnapshot().get("minTimeElapsed"));
    }

    @Test
    void aWarningIsTheStandardTierNotSevere() {
        PunishmentEntity warning = givenWarning();
        bed.eloBed.clock.advance(Duration.ofDays(15));

        EligibilityResult result = service.evaluate(warning, OWNER);

        assertFalse(result.severe(), "a warning is never a permanent network ban");
        assertEquals("STANDARD", result.checklistSnapshot().get("severityTier"));
    }
}
