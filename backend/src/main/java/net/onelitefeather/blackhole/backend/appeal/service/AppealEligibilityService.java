package net.onelitefeather.blackhole.backend.appeal.service;

import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.appeal.EligibilityResult;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.phoca.metadata.Metadata;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fixed, versioned, configuration-driven checklist - not free text per appeal - that
 * operationalizes "ELO-recovery-based auto-eligibility" while still requiring a human review
 * step (a pure auto-eligibility gate alone would create a farming vector: rack up clean days to
 * automatically lift a severe ban). {@link #CHECKLIST_VERSION} is stored on every result so a
 * future checklist change never retroactively reinterprets an already-decided appeal.
 */
@Singleton
public class AppealEligibilityService {

    public static final int CHECKLIST_VERSION = 2;

    /** Key in the appeal metadata of the appeal-conditions version the player accepted when filing. */
    public static final String META_CONDITIONS_VERSION = "conditionsVersion";

    private final AppealRepository appealRepository;
    private final PunishmentRepository punishmentRepository;
    private final EloScoring eloScoring;
    private final SectionService sectionService;
    private final CaseTimeline caseTimeline;
    private final Clock clock;
    private final int minDaysManual;
    private final int minDaysAuto;
    private final int repeatAppealCooldownDays;

    public AppealEligibilityService(
            AppealRepository appealRepository,
            PunishmentRepository punishmentRepository,
            EloScoring eloScoring,
            SectionService sectionService,
            CaseTimeline caseTimeline,
            Clock clock,
            @Value("${blackhole.appeal.min-days-manual:14}") int minDaysManual,
            @Value("${blackhole.appeal.min-days-auto:3}") int minDaysAuto,
            @Value("${blackhole.appeal.repeat-appeal-cooldown-days:7}") int repeatAppealCooldownDays
    ) {
        this.appealRepository = appealRepository;
        this.punishmentRepository = punishmentRepository;
        this.eloScoring = eloScoring;
        this.sectionService = sectionService;
        this.caseTimeline = caseTimeline;
        this.clock = clock;
        this.minDaysManual = minDaysManual;
        this.minDaysAuto = minDaysAuto;
        this.repeatAppealCooldownDays = repeatAppealCooldownDays;
    }

    /**
     * Looks up the punishment being appealed, evaluates it against the eligibility checklist,
     * and records the resulting appeal - the full submission flow so {@code AppealController}
     * never has to touch {@link PunishmentRepository}/{@link AppealRepository} directly.
     *
     * @return the saved appeal, or empty if {@code punishmentIdentifier} doesn't match any
     * existing punishment (submission is rejected, nothing is recorded)
     */
    public Optional<AppealEntity> submitAppeal(String punishmentIdentifier, String appellantHash, String statement) {
        PunishmentEntity punishment = this.punishmentRepository.findById(punishmentIdentifier).orElse(null);
        if (punishment == null) {
            return Optional.empty();
        }

        EligibilityResult eligibility = evaluate(punishment, appellantHash);
        long now = this.clock.millis();

        AppealEntity appeal = new AppealEntity(
                punishment, appellantHash, statement,
                eligibility.eligible() ? AppealStatus.ELIGIBLE_PENDING_REVIEW : AppealStatus.INELIGIBLE,
                eligibility.checklistSnapshot(), now, now, new HashMap<>()
        );
        AppealEntity saved = this.appealRepository.save(appeal);
        this.caseTimeline.appealFiled(saved);
        return Optional.of(saved);
    }

    /** Whether a player may file now and, if not, when: the checklist's two hard gates as dates. */
    public record Availability(State state, long availableFrom) {

        /** Why filing is closed, or that it is open. */
        public enum State {
            /** The minimum waiting time since the punishment has not passed. */
            NOT_YET,
            /** Filing is open. */
            AVAILABLE,
            /** A repeat appeal has to wait for the cooldown after an earlier one. */
            COOLDOWN
        }
    }

    /**
     * When the hard gates of {@link #evaluate} let a player file: the minimum time since the punishment and
     * the cooldown after an earlier denied (or recorded ineligible) appeal. Read-only. It answers
     * {@code AVAILABLE} exactly when {@link #evaluate} would say {@code eligible}.
     */
    public Availability availability(PunishmentEntity punishment, long now) {
        long creationDate = ((Number) punishment.getMetaData().get(Metadata.META_DATA_KEY_CREATION_DATE)).longValue();
        boolean isAutoTriggered = EloService.SYSTEM_ELO_SOURCE.equals(punishment.getSource());
        long readyAt = creationDate + Duration.ofDays(isAutoTriggered ? this.minDaysAuto : this.minDaysManual).toMillis();
        long cooldownMillis = Duration.ofDays(this.repeatAppealCooldownDays).toMillis();
        long cooldownEnd = this.appealRepository.findByPunishmentIdentifierAndStatusIn(
                punishment.getIdentifier(), List.of(AppealStatus.DENIED, AppealStatus.INELIGIBLE)
        ).stream().mapToLong(prior -> prior.getCreatedAt() + cooldownMillis).max().orElse(0L);
        long availableFrom = Math.max(readyAt, cooldownEnd);
        if (now >= availableFrom) {
            return new Availability(Availability.State.AVAILABLE, availableFrom);
        }
        return new Availability(now < readyAt ? Availability.State.NOT_YET : Availability.State.COOLDOWN, availableFrom);
    }

    /**
     * Files an appeal for the player only if the checklist lets them: an appeal that is not eligible is
     * <em>not</em> recorded (staff intake via {@link #submitAppeal} still records those). The database allows
     * one open appeal per punishment, so a concurrent second filing fails on the write.
     *
     * @return the saved appeal, empty when the punishment does not exist or the player may not file
     */
    public Optional<AppealEntity> fileIfEligible(PunishmentEntity punishment, String statement) {
        return fileIfEligible(punishment, statement, null);
    }

    /**
     * Like {@link #fileIfEligible(PunishmentEntity, String)}, and records the version of the appeal conditions the player accepted
     * under {@link #META_CONDITIONS_VERSION} in the appeal's metadata: the number only, nothing else about the acceptance.
     *
     * @param conditionsVersion the accepted version, {@code null} to record none
     */
    public Optional<AppealEntity> fileIfEligible(PunishmentEntity punishment, String statement, @Nullable Integer conditionsVersion) {
        EligibilityResult eligibility = evaluate(punishment, punishment.getOwner());
        if (!eligibility.eligible()) {
            return Optional.empty();
        }
        long now = this.clock.millis();
        Map<String, Object> metaData = new HashMap<>();
        if (conditionsVersion != null) {
            metaData.put(META_CONDITIONS_VERSION, conditionsVersion);
        }
        AppealEntity saved = this.appealRepository.save(new AppealEntity(
                punishment, punishment.getOwner(), statement, AppealStatus.ELIGIBLE_PENDING_REVIEW,
                eligibility.checklistSnapshot(), now, now, metaData));
        this.caseTimeline.appealFiled(saved);
        return Optional.of(saved);
    }

    /**
     * Plain, read-only pass-through so {@code AppealController} never has to inject
     * {@link AppealRepository} itself just to list appeals.
     */
    public Page<AppealEntity> findAll(Pageable pageable) {
        return this.appealRepository.findAll(pageable);
    }

    public EligibilityResult evaluate(PunishmentEntity punishment, String appellantHash) {
        long now = this.clock.millis();
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

        String ownSection = punishment.getSectionKey();
        List<SectionDTO.Response> supportingSections = this.sectionService.listEnabled().stream()
                .filter(section -> !section.key().equals(ownSection))
                .toList();
        boolean supportingRecovered = true;
        Integer lowestSupportingScore = null;
        for (SectionDTO.Response section : supportingSections) {
            int score = this.eloScoring.score(appellantHash, section.key()).orElse(section.baseScore());
            lowestSupportingScore = lowestSupportingScore == null ? score : Math.min(lowestSupportingScore, score);
            supportingRecovered &= score >= section.baseScore();
        }

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
        checklist.put("supportingEloSections", supportingSections.stream().map(SectionDTO.Response::key).toList());
        checklist.put("supportingEloScore", lowestSupportingScore);
        checklist.put("supportingEloRecovered", supportingRecovered);
        checklist.put("eligible", eligible);

        return new EligibilityResult(eligible, severe, checklist);
    }

    /**
     * A permanent network-wide (BAN) punishment is the one severe tier, which an appeal can only
     * shorten, never lift outright.
     */
    private boolean isSevere(PunishmentEntity punishment) {
        return punishment.isPermanent() && this.sectionService.find(punishment.getSectionKey())
                .map(section -> section.enforcement() == EnforcementKind.BAN)
                .orElse(false);
    }
}
