package net.onelitefeather.blackhole.backend.punishment.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.service.PunishmentEvidenceGateway;
import net.onelitefeather.blackhole.backend.kick.KickValidator;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.kick.service.KickService;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.ProfileSlots;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvents;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.punishment.SectionPunisher;
import net.onelitefeather.blackhole.backend.punishment.StepKind;
import net.onelitefeather.blackhole.backend.punishment.StepSelector;
import net.onelitefeather.blackhole.backend.warning.WarningEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import net.onelitefeather.blackhole.backend.utils.Durations;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleTemplateApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one chokepoint every punishment goes through: staff templates (direct or from a resolved
 * report), the ELO system's automatic punishments, and revocation. Each section has its own active
 * slot per player, so applying a punishment only ever replaces the active punishment of its own
 * section.
 *
 * <p>Applying a template picks the escalation step from the owner's earlier punishments in the
 * template's group, records the step and its cost on the punishment, occupies the section's slot and
 * only then deducts the cost - so a deduction that escalates replaces the staff punishment with the
 * permanent one instead of being overwritten by it.</p>
 *
 * <p>The slot write is atomic per (owner, section) row ({@link ProfileSlots#occupy}): a concurrent
 * apply in another section, or a telemetry update, is never overwritten, and two concurrent applies
 * in the same section end with one active punishment and the other in the history. A punishment is
 * saved before it can occupy the slot (the slot references it); if occupying fails or is refused it
 * is deleted again, so no orphan counts towards escalation. {@code @Transactional} is unusable in this
 * codebase (see {@link EloService}'s class Javadoc), so the sequence "read profile / save punishment /
 * occupy slot / deduct" as a whole is still not one transaction: the permanent-punishment pre-check in
 * {@link #apply} can be stale, which {@code occupy} closes by never replacing a permanent punishment.</p>
 */
@Singleton
public class PunishmentApplicationService implements SectionPunisher, ClientRuleTemplateApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentApplicationService.class);

    /** The answer to an {@code onBehalfOf} from any role but the proxy. */
    static final String ON_BEHALF_OF_PROXY_ONLY = "onBehalfOf is only accepted from the proxy";

    /** Metadata key naming the client rule a punishment came from. */
    static final String CLIENT_RULE_METADATA_KEY = "clientRule";

    private static final String SOFT_REASON = "Automatic temporary punishment (ELO system)";

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final ProfileSlots slots;
    private final PunishmentTemplateRepository templateRepository;
    private final SectionService sectionService;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final PunishmentEvidenceGateway evidence;
    private final CaseTimeline caseTimeline;
    private final Provider<EloScoring> eloScoring;
    private final StaffLinkService staffLinks;
    private final Clock clock;
    private final BanIdService banIds;
    private final KickService kicks;
    private final WarningEventPublisher warningEvents;
    private final StaffAuditRecorder audit;
    private final Duration defaultEscalationWindow;

    /**
     * @param eloScoring lazy - {@link EloService} itself depends on this service (to apply
     *                   automatic punishments), so a direct constructor-injected scoring bean would
     *                   form a circular dependency. A {@link Provider} defers resolution until first used.
     */
    public PunishmentApplicationService(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            ProfileSlots slots,
            PunishmentTemplateRepository templateRepository,
            SectionService sectionService,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            PunishmentEvidenceGateway evidence,
            CaseTimeline caseTimeline,
            Provider<EloScoring> eloScoring,
            StaffLinkService staffLinks,
            Clock clock,
            BanIdService banIds,
            KickService kicks,
            WarningEventPublisher warningEvents,
            StaffAuditRecorder audit,
            @Value("${blackhole.punishment.escalation.default-window:P365D}") Duration defaultEscalationWindow
    ) {
        this.audit = audit;
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.slots = slots;
        this.templateRepository = templateRepository;
        this.sectionService = sectionService;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.evidence = evidence;
        this.caseTimeline = caseTimeline;
        this.eloScoring = eloScoring;
        this.staffLinks = staffLinks;
        this.clock = clock;
        this.banIds = banIds;
        this.kicks = kicks;
        this.warningEvents = warningEvents;
        this.defaultEscalationWindow = defaultEscalationWindow;
    }

    /**
     * What {@link #apply} would do for the same request right now, without changing anything: the
     * escalation step the server selects, its cost, and whether the cost would leave the score below
     * the section's permanent threshold. Shares every check and the step selection with
     * {@link #apply}, so the preview cannot disagree with the application.
     *
     * @return the {@link PunishmentDTO.PreviewResponse}, or the {@link PunishmentDTO.Error} {@link #apply} would answer
     */
    public PunishmentDTO preview(PunishmentDTO.ApplyRequest request, Actor actor) {
        Planned planned = plan(request, actor);
        if (planned.error() != null) {
            return planned.error();
        }
        SectionDTO.Response section = planned.plan().section();
        StepSelector.Selection selection = planned.plan().selection();
        int cost = selection.step().getEloCost();
        int scoreBefore = this.eloScoring.get().score(request.owner(), section.key()).orElse(section.baseScore());
        int scoreAfter = scoreBefore - cost;
        return new PunishmentDTO.PreviewResponse(
                section.key(), section.enforcement(), selection.index() + 1, selection.stepCount(),
                selection.step().getDuration(), cost, scoreBefore, scoreAfter, section.permanentThreshold(),
                cost > 0 && scoreAfter < section.permanentThreshold()
        );
    }

    /** Everything {@link #apply} and {@link #preview} agree on before they diverge. */
    private record Plan(PunishmentTemplateEntity template, SectionDTO.Response section, long now, String group,
                        StepSelector.Selection selection, Attribution attribution) {
    }

    private record Planned(Plan plan, PunishmentDTO.Error error) {
    }

    private static Planned failed(ErrorKind kind, String message) {
        return new Planned(null, new PunishmentDTO.Error(message, kind));
    }

    /** Validates the request and selects the escalation step, or says why the template cannot be applied. */
    private Planned plan(PunishmentDTO.ApplyRequest request, Actor actor) {
        if (!OwnerHash.isValid(request.owner())) {
            return failed(ErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        Attribution attribution = actor.attribution(request.onBehalfOf()).orElse(null);
        if (attribution == null) {
            return failed(ErrorKind.VALIDATION, ON_BEHALF_OF_PROXY_ONLY);
        }
        return plan(request, attribution);
    }

    private Planned plan(PunishmentDTO.ApplyRequest request, Attribution attribution) {
        if (!OwnerHash.isValid(request.owner())) {
            return failed(ErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        boolean hasKey = request.templateKey() != null && !request.templateKey().isBlank();
        if (hasKey == (request.templateId() != null)) {
            return failed(ErrorKind.VALIDATION, "exactly one of templateKey and templateId is required");
        }

        PunishmentTemplateEntity template = (hasKey
                ? this.templateRepository.findByTemplateKey(request.templateKey())
                : this.templateRepository.findById(request.templateId())).orElse(null);
        if (template == null) {
            return failed(ErrorKind.NOT_FOUND, "there is no such punishment template");
        }
        SectionDTO.Response section = this.sectionService.find(template.getSectionKey()).orElse(null);
        if (section == null) {
            return failed(ErrorKind.NOT_FOUND, "the template's section '" + template.getSectionKey() + "' does not exist");
        }
        if (!section.enabled()) {
            return failed(ErrorKind.CONFLICT, "section '" + section.key() + "' is disabled");
        }

        String owner = request.owner();
        long now = this.clock.millis();
        String group = template.getEscalationGroup() != null ? template.getEscalationGroup() : template.getTemplateKey();
        Duration window = Durations.parsePositive(template.getEscalationWindow()).orElse(this.defaultEscalationWindow);
        long counted = countEarlier(owner, group, template, now, window);
        StepSelector.Selection selection = StepSelector.select(template.getSteps(), counted);

        if (Boolean.TRUE.equals(request.warningOnly()) && selection.step().getKind() != StepKind.WARN) {
            return failed(ErrorKind.WARN_STEP_REQUIRED, "the template's next step for this player is "
                    + selection.step().getKind() + ", not a warning; apply it as a punishment instead");
        }
        if (selection.step().getKind() == StepKind.WARN) {
            // A warning never takes the slot and never blocks: the template is all there is to check.
            return new Planned(new Plan(template, section, now, group, selection, attribution), null);
        } else if (selection.step().getKind() == StepKind.KICK) {
            // A kick never takes the slot, so an existing permanent punishment does not stand in its way.
            Optional<KickDTO.Error> invalid = this.kicks.checkTemplateKick(owner, request.scope(), request.target(), kickReason(template));
            if (invalid.isPresent()) {
                return failed(invalid.get().kind(), invalid.get().errorMessage());
            }
        } else {
            PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
            PunishmentEntity current = profile == null ? null : profile.getActive().get(section.key());
            if (current != null && current.isPermanent()) {
                return failed(ErrorKind.CONFLICT, "the player is already permanently punished in section '" + section.key() + "'");
            }
        }
        return new Planned(new Plan(template, section, now, group, selection, attribution), null);
    }

    /**
     * How many earlier punishments count towards the template's escalation group: every row that is not lifted and
     * is inside the escalation window, except warnings; plus the warnings inside the warn window (the escalation
     * window when the template sets none). A warning older than the warn window stays in the history but stops
     * climbing the ladder.
     */
    private long countEarlier(String owner, String group, PunishmentTemplateEntity template, long now, Duration window) {
        long all = this.punishmentRepository.countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals(
                owner, group, now - window.toMillis());
        long warningsInWindow = this.punishmentRepository.countWarningsByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals(
                owner, group, now - window.toMillis());
        Duration warnWindow = Durations.parsePositive(template.getWarnWindow()).orElse(window);
        long warningsInWarnWindow = warnWindow.equals(window) ? warningsInWindow
                : this.punishmentRepository.countWarningsByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals(
                owner, group, now - warnWindow.toMillis());
        return all - warningsInWindow + warningsInWarnWindow;
    }

    /**
     * Applies a template to the owner: a temporary punishment in the template's section whose
     * duration and ELO cost come from the escalation step the owner has reached. Creates the
     * profile if this is their first punishment.
     *
     * @return the {@link PunishmentDTO.ApplyResponse}, or a {@link PunishmentDTO.Error} for an
     * invalid request, an unknown template or section, a disabled section, or an owner that is
     * already permanently punished in the section, or an {@code onBehalfOf} from a caller that is not
     * the proxy - in every error case nothing changed
     */
    public PunishmentDTO apply(PunishmentDTO.ApplyRequest request, Actor actor) {
        Planned planned = plan(request, actor);
        if (planned.error() != null) {
            return planned.error();
        }
        PunishmentDTO result = execute(request, planned.plan(), Map.of(), false);
        if (result instanceof PunishmentDTO.ApplyResponse applied) {
            this.audit.record(planned.plan().attribution(), actor, AuditAction.PUNISHMENT_APPLY,
                    AuditTarget.ofCase(PunishmentShortId.storageKey(applied.punishment().identifier())));
        }
        return result;
    }

    /**
     * Applies a client rule's template for the system: the step the owner has reached, its ELO cost and a
     * Ban ID, with the ELO system as source (so the punishment counts as automatic, appeals wait like for
     * any automatic punishment) and the rule recorded in the metadata. Nothing is added while the owner
     * still has an active punishment from the same rule in the template's section, so a repeated hit
     * never escalates the step.
     */
    @Override
    public ClientRuleTemplateApplier.Result applyForRule(String owner, String templateKey, String ruleKey) {
        PunishmentDTO.ApplyRequest request = new PunishmentDTO.ApplyRequest(owner, templateKey, null, null);
        Planned planned = plan(request, new Attribution(EloService.SYSTEM_ELO_SOURCE, null));
        if (planned.error() != null) {
            LOGGER.warn("Client rule {} could not apply template {}: {}", ruleKey, templateKey, planned.error().errorMessage());
            return new ClientRuleTemplateApplier.Result(ClientRuleTemplateApplier.Outcome.NOT_APPLIED, null);
        }
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        PunishmentEntity current = profile == null ? null : profile.getActive().get(planned.plan().section().key());
        if (current != null && ruleKey.equals(current.getMetaData().get(CLIENT_RULE_METADATA_KEY)) && !isExpired(current, planned.plan().now())) {
            return new ClientRuleTemplateApplier.Result(ClientRuleTemplateApplier.Outcome.ALREADY_ACTIVE, null);
        }
        Map<String, Object> extra = Map.of("automatic", true, CLIENT_RULE_METADATA_KEY, ruleKey, "automaticSource", "client-rule:" + ruleKey);
        PunishmentDTO result = execute(request, planned.plan(), extra, true);
        if (result instanceof PunishmentDTO.ApplyResponse applied) {
            return new ClientRuleTemplateApplier.Result(ClientRuleTemplateApplier.Outcome.APPLIED, applied.punishment().identifier());
        }
        return new ClientRuleTemplateApplier.Result(ClientRuleTemplateApplier.Outcome.NOT_APPLIED, null);
    }

    private static boolean isExpired(PunishmentEntity punishment, long now) {
        Object expiry = punishment.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE);
        return expiry instanceof Number number && number.longValue() <= now;
    }

    /** The shared tail of {@link #apply} and {@link #applyForRule}: everything after the plan. */
    private PunishmentDTO execute(PunishmentDTO.ApplyRequest request, Plan plan, Map<String, Object> extraMetadata, boolean chargeSystemSource) {
        PunishmentTemplateEntity template = plan.template();
        SectionDTO.Response section = plan.section();
        String owner = request.owner();
        long now = plan.now();
        String group = plan.group();
        StepSelector.Selection selection = plan.selection();
        Attribution attribution = this.staffLinks.withLinkedActor(plan.attribution());
        int cost = selection.step().getEloCost();

        PunishmentEntity saved;
        if (selection.step().getKind() == StepKind.WARN) {
            saved = recordWarning(owner, template, attribution, group, selection, now);
        } else if (selection.step().getKind() == StepKind.KICK) {
            // The kick path: a KICK row of the group, no Ban ID, no slot, nothing active. Only the cost below is shared.
            saved = this.kicks.kickForTemplate(owner, request.scope(), request.target(), kickReason(template), attribution,
                    template, group, selection.index(), selection.stepCount(), cost);
        } else {
            Map<String, Object> metadata = new HashMap<>(extraMetadata);
            metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, now);
            metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
            metadata.put(Expirable.META_DATA_KEY_EXPIRATION_DATE, now + selection.step().parsedDuration().toMillis());

            PunishmentEntity punishment = new PunishmentEntity(
                    IdGenerator.generateId(), attribution.source(), owner, section.key(), false, now, template, metadata
            );
            punishment.setRecordedByOid(attribution.recordedByOid());
            punishment.setActorOid(attribution.actorOid());
            punishment.recordEscalation(group, selection.index(), selection.stepCount(), cost);
            saved = this.punishmentRepository.save(punishment);
            this.banIds.issue(saved.getIdentifier());
            if (!place(owner, section, saved)) {
                return error(ErrorKind.CONFLICT, "the player is already permanently punished in section '" + section.key() + "'");
            }
        }
        claimEvidence(request.evidenceCorrelationId(), owner, saved);

        int scoreBefore;
        int scoreAfter;
        PunishmentDTO.Escalation escalation = null;
        EloScoring scoring = this.eloScoring.get();
        if (cost > 0 && (chargeSystemSource || !EloService.SYSTEM_ELO_SOURCE.equals(attribution.source()))) {
            EloChange change = scoring.applyDelta(owner, section.key(), -cost, EloReasonCode.PUNISHMENT_APPLIED, null, Map.of(
                    "punishmentIdentifier", saved.getIdentifier(),
                    "templateIdentifier", template.getIdentifier().toString()
            ));
            if (change instanceof EloChange.Applied applied) {
                scoreBefore = applied.scoreBefore();
                scoreAfter = applied.scoreAfter();
                escalation = applied.automatic()
                        .filter(automatic -> automatic.tier() == EloChange.Tier.PERMANENT)
                        .flatMap(automatic -> this.punishmentRepository.findById(automatic.punishmentIdentifier()))
                        .map(permanent -> new PunishmentDTO.Escalation(withEvidenceCount(permanent), applied.scoreAfter()))
                        .orElse(null);
            } else {
                LOGGER.warn("Could not deduct the ELO cost of {} in section {}: {}", saved.getIdentifier(), section.key(),
                        ((EloChange.Rejected) change).message());
                scoreBefore = scoring.score(owner, section.key()).orElse(section.baseScore());
                scoreAfter = scoreBefore;
            }
        } else {
            scoreBefore = scoring.score(owner, section.key()).orElse(section.baseScore());
            scoreAfter = scoreBefore;
        }

        if (selection.step().getKind() == StepKind.PUNISH) {
            this.caseTimeline.applied(saved, scoreBefore, scoreAfter);
        }
        return new PunishmentDTO.ApplyResponse(
                withEvidenceCount(saved), section.key(), section.enforcement(),
                selection.index() + 1, selection.stepCount(), scoreBefore, scoreAfter, escalation
        );
    }

    /**
     * Stores the warning a WARN step stands for: a WARN row of the template's escalation group, with a Ban ID and in the
     * player's history, never in an active slot. Charging the step's ELO cost is the caller's job.
     */
    private PunishmentEntity recordWarning(String owner, PunishmentTemplateEntity template, Attribution attribution,
                                           String group, StepSelector.Selection selection, long now) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, now);
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
        metadata.put("reason", kickReason(template));
        PunishmentEntity warning = new PunishmentEntity(
                IdGenerator.generateId(), attribution.source(), owner, null, false, now, template, metadata);
        warning.recordWarning();
        warning.recordEscalation(group, selection.index(), selection.stepCount(), selection.step().getEloCost());
        warning.setRecordedByOid(attribution.recordedByOid());
        warning.setActorOid(attribution.actorOid());
        PunishmentEntity saved = this.punishmentRepository.save(warning);
        this.banIds.issue(saved.getIdentifier());
        try {
            this.slots.appendHistory(owner, saved.getIdentifier());
            this.profileCache.evict(owner);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not add warning {} to the player's history", saved.getIdentifier(), e);
        }
        this.caseTimeline.warned(saved, template.getTemplateKey(), selection.step().getEloCost());
        this.warningEvents.warned(saved.getIdentifier(), owner, now);
        return saved;
    }

    /** The reason a template kick shows the player: the template's reason, cut to what a kick may carry. */
    private static String kickReason(PunishmentTemplateEntity template) {
        String reason = template.getReason() == null ? "" : template.getReason().strip();
        return reason.length() > KickValidator.MAX_REASON_LENGTH ? reason.substring(0, KickValidator.MAX_REASON_LENGTH) : reason;
    }

    /**
     * Attaches the evidence sent before the punishment existed. The punishment already stands, so a
     * failure here is logged and the evidence stays pending (and expires) rather than failing the apply.
     */
    private void claimEvidence(String correlationId, String owner, PunishmentEntity punishment) {
        if (correlationId == null || correlationId.isBlank()) {
            return;
        }
        try {
            this.evidence.claimPending(owner, correlationId, punishment.getIdentifier());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not claim the pending evidence {} for punishment {}", correlationId, punishment.getIdentifier(), e);
        }
    }

    /**
     * Marks the revoked punishment's evidence {@code REVOKED}. The revoke already happened, so a
     * failure here is logged and never fails it.
     */
    private void markEvidenceRevoked(PunishmentEntity punishment, Attribution by) {
        try {
            this.evidence.markRevoked(punishment.getIdentifier(), this.staffLinks.withLinkedActor(by));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not mark the evidence of revoked punishment {}", punishment.getIdentifier(), e);
        }
    }

    /** Records the revoke in the case. The punishment is already revoked, so nothing here may fail it. */
    private void recordRevoked(PunishmentEntity punishment, Attribution attribution) {
        try {
            this.caseTimeline.revoked(punishment, this.staffLinks.withLinkedActor(attribution));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not record the revoke of punishment {} in its case", punishment.getIdentifier(), e);
        }
    }

    @Override
    public boolean hasActivePermanent(String owner, String sectionKey) {
        return this.profileRepository.findById(owner)
                .map(profile -> profile.getActive().get(sectionKey))
                .filter(PunishmentEntity::canBeActive)
                .map(PunishmentEntity::isPermanent)
                .orElse(false);
    }

    @Override
    public Optional<String> applyPermanent(String owner, SectionDTO.Response section, Map<String, Object> extraMetaData) {
        long now = this.clock.millis();
        Map<String, Object> metadata = new HashMap<>(extraMetaData);
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, now);
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
        metadata.put("reason", section.permanentReason());
        metadata.put("automatic", true);

        PunishmentEntity punishment = new PunishmentEntity(
                IdGenerator.generateId(), EloService.SYSTEM_ELO_SOURCE, owner, section.key(), true, now, null, metadata
        );
        PunishmentEntity saved = this.punishmentRepository.save(punishment);
        this.banIds.issue(saved.getIdentifier());
        return placeAndRecord(owner, section, saved);
    }

    @Override
    public Optional<String> applySoft(String owner, SectionDTO.Response section, Duration duration, Map<String, Object> extraMetaData) {
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        PunishmentEntity current = profile == null ? null : profile.getActive().get(section.key());
        if (current != null && current.isPermanent()) {
            return Optional.empty();
        }
        long now = this.clock.millis();
        Map<String, Object> metadata = new HashMap<>(extraMetaData);
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, now);
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
        metadata.put(Expirable.META_DATA_KEY_EXPIRATION_DATE, now + duration.toMillis());
        metadata.put("reason", SOFT_REASON);
        metadata.put("automatic", true);

        PunishmentEntity punishment = new PunishmentEntity(
                IdGenerator.generateId(), EloService.SYSTEM_ELO_SOURCE, owner, section.key(), false, now, null, metadata
        );
        PunishmentEntity saved = this.punishmentRepository.save(punishment);
        this.banIds.issue(saved.getIdentifier());
        return placeAndRecord(owner, section, saved);
    }

    /** Places an automatic punishment and, once it stands, records it in its case. */
    private Optional<String> placeAndRecord(String owner, SectionDTO.Response section, PunishmentEntity saved) {
        if (!place(owner, section, saved)) {
            return Optional.empty();
        }
        this.caseTimeline.applied(saved, null, null);
        return Optional.of(saved.getIdentifier());
    }

    /**
     * Makes {@code punishment} the section's active punishment (the one it replaces moves into
     * history), creating the profile if needed, then evicts the cached profile and announces the
     * punishment. If the slot cannot be taken the already saved punishment is discarded again so it
     * cannot count towards escalation.
     *
     * @return {@code false} if a permanent punishment holds the slot and the punishment was discarded
     */
    private boolean place(String owner, SectionDTO.Response section, PunishmentEntity punishment) {
        ProfileSlots.Placement placement;
        try {
            placement = this.slots.occupy(owner, section.key(), punishment.getIdentifier());
        } catch (RuntimeException e) {
            discardOrphan(punishment);
            throw e;
        }
        if (!placement.occupied()) {
            discardOrphan(punishment);
            return false;
        }
        this.profileCache.evict(owner);

        if (placement.profileCreated()) {
            this.eventPublisher.publish("profile.created", Map.of("owner", owner));
        }
        this.eventPublisher.publish("punishment.created", PunishmentEvents.created(punishment, section));
        return true;
    }

    /** Removes a punishment that never became active; if it cannot be deleted it is lifted instead. */
    private void discardOrphan(PunishmentEntity punishment) {
        try {
            this.punishmentRepository.delete(punishment);
        } catch (RuntimeException deleteFailure) {
            LOGGER.warn("Could not delete the unplaced punishment {}; lifting it instead", punishment.getIdentifier(), deleteFailure);
            punishment.setLifted(true);
            this.punishmentRepository.update(punishment);
        }
    }

    /**
     * Revokes the owner's active punishment in one section: it moves to history, is marked lifted
     * so it no longer counts for escalation, and - if it was permanent - the owner's score in the
     * section is reset to the section's pardon score.
     *
     * <p>A staff revoke of a temporary punishment does <b>not</b> refund its ELO cost; only a granted
     * appeal that fully lifts it does (see the appeal decision service).</p>
     *
     * @return the {@link PunishmentDTO.RevokeResponse}, or a {@link PunishmentDTO.Error} if the
     * request is invalid or the owner has no active punishment in that section
     */
    public PunishmentDTO revoke(PunishmentDTO.RevokeRequest request, Actor actor) {
        if (!OwnerHash.isValid(request.owner())) {
            return error(ErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        Attribution attribution = actor.attribution(request.onBehalfOf()).orElse(null);
        if (attribution == null) {
            return error(ErrorKind.VALIDATION, ON_BEHALF_OF_PROXY_ONLY);
        }
        String owner = request.owner();
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        PunishmentEntity active = profile == null ? null : profile.getActive().get(request.section());
        if (active == null) {
            return error(ErrorKind.NOT_FOUND, "the player has no active punishment in section '" + request.section() + "'");
        }

        // Clears only if the slot still holds the punishment we read, so a punishment applied
        // concurrently in the meantime is never removed by mistake.
        if (!this.slots.clearIf(owner, request.section(), active.getIdentifier())) {
            return error(ErrorKind.NOT_FOUND, "the player has no active punishment in section '" + request.section() + "'");
        }
        active.getMetaData().put("revokedBy", attribution.source().toString());
        active.getMetaData().put("recordedByOid", attribution.recordedByOid().toString());
        active.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, this.clock.millis());
        active.setLifted(true);
        this.punishmentRepository.update(active);
        this.profileCache.evict(owner);
        markEvidenceRevoked(active, attribution);
        recordRevoked(active, attribution);

        Integer scoreReset = null;
        if (active.isPermanent()) {
            scoreReset = this.eloScoring.get().resetToPardon(owner, request.section(), Map.of(
                    "punishmentIdentifier", active.getIdentifier(),
                    "revokedBy", attribution.source().toString(),
                    "recordedByOid", attribution.recordedByOid().toString()
            )).orElse(null);
        }

        SectionDTO.Response section = this.sectionService.find(request.section()).orElse(null);
        this.eventPublisher.publish("punishment.revoked", PunishmentEvents.revoked(active, section, attribution.source().toString(), attribution.recordedByOid()));
        this.audit.record(attribution, actor, AuditAction.PUNISHMENT_REVOKE, AuditTarget.ofCase(active.getShortId()));

        return new PunishmentDTO.RevokeResponse(withEvidenceCount(active), request.section(), scoreReset);
    }

    /**
     * Returns a paginated list of all punishment entries in the system.
     */
    public Page<PunishEntryDTO> findAll(Pageable pageable) {
        return this.punishmentRepository.findAll(pageable).map(this::withEvidenceCount);
    }

    /**
     * Finds the punishments whose short ID matches what a player quoted.
     *
     * @param shortId the short ID as typed, with or without {@code #}, in any case
     * @return the {@link PunishmentDTO.ShortIdMatches} (empty if nothing matches), or a
     * {@link PunishmentDTO.Error} if the input is not exactly eight hex characters
     */
    public PunishmentDTO findByShortId(String shortId) {
        return PunishmentShortId.parse(shortId)
                .<PunishmentDTO>map(key -> new PunishmentDTO.ShortIdMatches(
                        this.punishmentRepository.findByShortId(key).stream().map(this::withEvidenceCount).toList()))
                .orElseGet(() -> error(ErrorKind.VALIDATION, "a short id is # followed by exactly eight hex characters"));
    }

    /**
     * Finds the punishment a Ban ID belongs to. The Ban ID arrives in a request body, never in a URL.
     * Anything that does not match, malformed or unknown alike, is the same {@code NOT_FOUND} without
     * the input in it.
     */
    public PunishmentDTO findByBanId(PunishmentDTO.LookupRequest request) {
        return this.banIds.findIdentifier(request.banId())
                .flatMap(this.punishmentRepository::findById)
                .<PunishmentDTO>map(punishment -> new PunishmentDTO.LookupResponse(withEvidenceCount(punishment)))
                .orElseGet(() -> error(ErrorKind.NOT_FOUND, "there is no such punishment"));
    }

    /** The punishment as a DTO that tells how many valid evidence items it has, and nothing more about them, and who linked staff member issued it. */
    private PunishEntryDTO withEvidenceCount(PunishmentEntity punishment) {
        PunishEntryDTO entry = punishment.toDTO().withEvidenceCount(this.evidence.countValid(punishment.getIdentifier()));
        if (punishment.getActorOid() == null) {
            return entry;
        }
        return entry.withActorName(this.staffLinks.displayName(punishment.getActorOid()).orElse(null));
    }

    private static PunishmentDTO.Error error(ErrorKind kind, String message) {
        return new PunishmentDTO.Error(message, kind);
    }
}
