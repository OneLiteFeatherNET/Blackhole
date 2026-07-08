package net.onelitefeather.blackhole.backend.appeal;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.AppealEntity;
import net.onelitefeather.blackhole.backend.database.entities.EloProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.repository.AppealRepository;
import net.onelitefeather.blackhole.backend.database.repository.EloProfileRepository;
import net.onelitefeather.blackhole.backend.dto.AppealStatus;
import net.onelitefeather.blackhole.backend.dto.EloTrack;
import net.onelitefeather.blackhole.backend.dto.PunishType;
import net.onelitefeather.blackhole.backend.elo.EloService;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A fixed, versioned, configuration-driven checklist - not free text per appeal - that
 * operationalizes "ELO-recovery-based auto-eligibility" while still requiring a human review
 * step (a pure auto-eligibility gate alone would create a farming vector: rack up clean days to
 * automatically lift a severe ban). {@link #CHECKLIST_VERSION} is stored on every result so a
 * future checklist change never retroactively reinterprets an already-decided appeal.
 */
@Singleton
public class AppealEligibilityService {

    public static final int CHECKLIST_VERSION = 1;

    private final AppealRepository appealRepository;
    private final EloProfileRepository eloProfileRepository;
    private final int minDaysManual;
    private final int minDaysAuto;
    private final int repeatAppealCooldownDays;
    private final int eloBaseline;

    public AppealEligibilityService(
            AppealRepository appealRepository,
            EloProfileRepository eloProfileRepository,
            @Value("${blackhole.appeal.min-days-manual:14}") int minDaysManual,
            @Value("${blackhole.appeal.min-days-auto:3}") int minDaysAuto,
            @Value("${blackhole.appeal.repeat-appeal-cooldown-days:7}") int repeatAppealCooldownDays,
            @Value("${blackhole.elo.baseline:1000}") int eloBaseline
    ) {
        this.appealRepository = appealRepository;
        this.eloProfileRepository = eloProfileRepository;
        this.minDaysManual = minDaysManual;
        this.minDaysAuto = minDaysAuto;
        this.repeatAppealCooldownDays = repeatAppealCooldownDays;
        this.eloBaseline = eloBaseline;
    }

    public EligibilityResult evaluate(PunishmentEntity punishment, String appellantHash) {
        long now = System.currentTimeMillis();
        long creationDate = ((Number) punishment.getMetaData().get(Metadata.META_DATA_KEY_CREATION_DATE)).longValue();
        String eloTriggerReasonCode = (String) punishment.getMetaData().get("eloTriggerReasonCode");
        boolean isAutoTriggered = EloService.SYSTEM_ELO_SOURCE.equals(punishment.getSource());

        int minDaysRequired = isAutoTriggered ? this.minDaysAuto : this.minDaysManual;
        double daysSincePunishment = (now - creationDate) / (double) Duration.ofDays(1).toMillis();
        boolean minTimeElapsed = daysSincePunishment >= minDaysRequired;

        List<AppealEntity> priorAppeals = this.appealRepository.findByPunishmentIdentifierAndStatusIn(
                punishment.getIdentifier(), List.of(AppealStatus.DENIED, AppealStatus.INELIGIBLE)
        );
        long cooldownMillis = Duration.ofDays(this.repeatAppealCooldownDays).toMillis();
        boolean isRepeatAppeal = priorAppeals.stream().anyMatch(prior -> (now - prior.getCreatedAt()) < cooldownMillis);

        boolean severe = isSevere(punishment);

        EloTrack supportingTrack = determineSupportingTrack(punishment, eloTriggerReasonCode);
        EloProfileEntity eloProfile = this.eloProfileRepository.findById(appellantHash).orElse(null);
        int supportingScore = eloProfile == null ? this.eloBaseline : eloProfile.getScore(supportingTrack);
        boolean supportingRecovered = supportingScore >= this.eloBaseline;

        boolean eligible = minTimeElapsed && !isRepeatAppeal;

        Map<String, Object> checklist = new HashMap<>();
        checklist.put("checklistVersion", CHECKLIST_VERSION);
        checklist.put("minDaysRequired", minDaysRequired);
        checklist.put("daysSincePunishment", daysSincePunishment);
        checklist.put("minTimeElapsed", minTimeElapsed);
        checklist.put("isRepeatAppeal", isRepeatAppeal);
        checklist.put("repeatAppealCooldownDays", this.repeatAppealCooldownDays);
        checklist.put("isAutoTriggered", isAutoTriggered);
        checklist.put("eloTriggerReasonCode", eloTriggerReasonCode);
        checklist.put("severityTier", severe ? "SEVERE" : "STANDARD");
        checklist.put("supportingEloTrack", supportingTrack.toString());
        checklist.put("supportingEloScore", supportingScore);
        checklist.put("supportingEloRecovered", supportingRecovered);
        checklist.put("eligible", eligible);

        return new EligibilityResult(eligible, severe, checklist);
    }

    private static boolean isSevere(PunishmentEntity punishment) {
        return punishment.getType() == PunishType.NETWORK && !punishment.getMetaData().containsKey(Expirable.META_DATA_KEY_EXPIRATION_DATE);
    }

    /**
     * The track that did NOT trigger this punishment - its recovery status is a supporting
     * good-faith signal for reviewers, not a hard gate. Falls back to inferring from the
     * punishment's own type when the precise triggering signal isn't recorded (e.g. a manually
     * staff-issued punishment, or a report-actioned one where either track could apply).
     */
    private static EloTrack determineSupportingTrack(PunishmentEntity punishment, String eloTriggerReasonCode) {
        if ("TOXICITY_FLAG".equals(eloTriggerReasonCode)) {
            return EloTrack.GAMEPLAY;
        }
        if ("ANTICHEAT_FLAG".equals(eloTriggerReasonCode)) {
            return EloTrack.CHAT;
        }
        return punishment.getType() == PunishType.CHAT ? EloTrack.GAMEPLAY : EloTrack.CHAT;
    }
}
