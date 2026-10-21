package net.onelitefeather.blackhole.backend.punishment.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloScoring;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvents;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.punishment.SectionPunisher;
import net.onelitefeather.blackhole.backend.punishment.StepSelector;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.utils.Durations;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * <p><b>Known limitation (deferred, not fixed here):</b> the read-profile / save-punishment /
 * occupy-slot / deduct sequence is not atomic - {@code @Transactional} is unusable in this codebase
 * (see {@link EloService}'s class Javadoc for why). Two concurrent applications to the same owner and
 * section could both read the same "slot empty" state; the primary key of {@code active_punishments}
 * rejects the second write rather than silently double-occupying the slot, but the caller sees an
 * unhandled persistence exception instead of a clean conflict. This is an accepted race window.</p>
 */
@Singleton
public class PunishmentApplicationService implements SectionPunisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentApplicationService.class);

    private static final String SOFT_REASON = "Automatic temporary punishment (ELO system)";

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final PunishmentTemplateRepository templateRepository;
    private final SectionService sectionService;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final Provider<EloScoring> eloScoring;
    private final Clock clock;
    private final Duration defaultEscalationWindow;

    /**
     * @param eloScoring lazy - {@link EloService} itself depends on this service (to apply
     *                   automatic punishments), so a direct constructor-injected scoring bean would
     *                   form a circular dependency. A {@link Provider} defers resolution until first used.
     */
    public PunishmentApplicationService(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            PunishmentTemplateRepository templateRepository,
            SectionService sectionService,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            Provider<EloScoring> eloScoring,
            Clock clock,
            @Value("${blackhole.punishment.escalation.default-window:P365D}") Duration defaultEscalationWindow
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
        this.sectionService = sectionService;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.eloScoring = eloScoring;
        this.clock = clock;
        this.defaultEscalationWindow = defaultEscalationWindow;
    }

    /**
     * Applies a template to the owner: a temporary punishment in the template's section whose
     * duration and ELO cost come from the escalation step the owner has reached. Creates the
     * profile if this is their first punishment.
     *
     * @return the {@link PunishmentDTO.ApplyResponse}, or a {@link PunishmentDTO.Error} for an
     * invalid request, an unknown template or section, a disabled section, or an owner that is
     * already permanently punished in the section - in every error case nothing changed
     */
    public PunishmentDTO apply(PunishmentDTO.ApplyRequest request) {
        if (!OwnerHash.isValid(request.owner())) {
            return error(ErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        if (request.source() == null) {
            return error(ErrorKind.VALIDATION, "source is required");
        }
        boolean hasKey = request.templateKey() != null && !request.templateKey().isBlank();
        if (hasKey == (request.templateId() != null)) {
            return error(ErrorKind.VALIDATION, "exactly one of templateKey and templateId is required");
        }

        PunishmentTemplateEntity template = (hasKey
                ? this.templateRepository.findByTemplateKey(request.templateKey())
                : this.templateRepository.findById(request.templateId())).orElse(null);
        if (template == null) {
            return error(ErrorKind.NOT_FOUND, "there is no such punishment template");
        }
        SectionDTO.Response section = this.sectionService.find(template.getSectionKey()).orElse(null);
        if (section == null) {
            return error(ErrorKind.NOT_FOUND, "the template's section '" + template.getSectionKey() + "' does not exist");
        }
        if (!section.enabled()) {
            return error(ErrorKind.CONFLICT, "section '" + section.key() + "' is disabled");
        }

        String owner = request.owner();
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        PunishmentEntity current = profile == null ? null : profile.getActive().get(section.key());
        if (current != null && current.isPermanent()) {
            return error(ErrorKind.CONFLICT, "the player is already permanently punished in section '" + section.key() + "'");
        }

        long now = this.clock.millis();
        String group = template.getEscalationGroup() != null ? template.getEscalationGroup() : template.getTemplateKey();
        Duration window = Durations.parsePositive(template.getEscalationWindow()).orElse(this.defaultEscalationWindow);
        long counted = this.punishmentRepository.countByOwnerAndEscalationGroupAndLiftedFalseAndCreatedAtGreaterThanEquals(
                owner, group, now - window.toMillis()
        );
        StepSelector.Selection selection = StepSelector.select(template.getSteps(), counted);
        int cost = selection.step().getEloCost();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, now);
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
        metadata.put(Expirable.META_DATA_KEY_EXPIRATION_DATE, now + selection.step().parsedDuration().toMillis());

        PunishmentEntity punishment = new PunishmentEntity(
                IdGenerator.generateId(), request.source(), owner, section.key(), false, now, template, metadata
        );
        punishment.recordEscalation(group, selection.index(), selection.stepCount(), cost);
        PunishmentEntity saved = this.punishmentRepository.save(punishment);
        place(profile, owner, section, saved);

        int scoreBefore;
        int scoreAfter;
        PunishmentDTO.Escalation escalation = null;
        EloScoring scoring = this.eloScoring.get();
        if (cost > 0 && !EloService.SYSTEM_ELO_SOURCE.equals(request.source())) {
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
                        .map(permanent -> new PunishmentDTO.Escalation(permanent.toDTO(), applied.scoreAfter()))
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

        return new PunishmentDTO.ApplyResponse(
                saved.toDTO(), section.key(), section.enforcement(),
                selection.index() + 1, selection.stepCount(), scoreBefore, scoreAfter, escalation
        );
    }

    @Override
    public boolean hasActivePermanent(String owner, String sectionKey) {
        return this.profileRepository.findById(owner)
                .map(profile -> profile.getActive().get(sectionKey))
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
        place(this.profileRepository.findById(owner).orElse(null), owner, section, saved);
        return Optional.of(saved.getIdentifier());
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
        place(profile, owner, section, saved);
        return Optional.of(saved.getIdentifier());
    }

    /**
     * Makes {@code punishment} the section's active punishment, moving the one it replaces into
     * history, creating the profile if needed, then evicts the cached profile and announces the
     * punishment.
     */
    private void place(PunishmentProfileEntity profile, String owner, SectionDTO.Response section, PunishmentEntity punishment) {
        boolean isNewProfile = profile == null;
        if (isNewProfile) {
            profile = new PunishmentProfileEntity(owner, new HashMap<>(), new ArrayList<>(), new HashMap<>());
        }
        PunishmentEntity replaced = profile.getActive().put(section.key(), punishment);
        if (replaced != null) {
            profile.getHistory().add(replaced);
        }
        if (isNewProfile) {
            this.profileRepository.save(profile);
        } else {
            this.profileRepository.update(profile);
        }
        this.profileCache.evict(owner);

        if (isNewProfile) {
            this.eventPublisher.publish("profile.created", Map.of("owner", owner));
        }
        this.eventPublisher.publish("punishment.created", PunishmentEvents.created(punishment, section));
    }

    /**
     * Revokes the owner's active punishment in one section: it moves to history, is marked lifted
     * so it no longer counts for escalation, and - if it was permanent - the owner's score in the
     * section is reset to the section's pardon score.
     *
     * @return the {@link PunishmentDTO.RevokeResponse}, or a {@link PunishmentDTO.Error} if the
     * request is invalid or the owner has no active punishment in that section
     */
    public PunishmentDTO revoke(PunishmentDTO.RevokeRequest request) {
        if (!OwnerHash.isValid(request.owner())) {
            return error(ErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        String owner = request.owner();
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        PunishmentEntity active = profile == null ? null : profile.getActive().get(request.section());
        if (active == null) {
            return error(ErrorKind.NOT_FOUND, "the player has no active punishment in section '" + request.section() + "'");
        }

        active.getMetaData().put("revokedBy", request.source().toString());
        active.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, this.clock.millis());
        active.setLifted(true);
        this.punishmentRepository.update(active);

        profile.getActive().remove(request.section());
        profile.getHistory().add(active);
        this.profileRepository.update(profile);
        this.profileCache.evict(owner);

        Integer scoreReset = null;
        if (active.isPermanent()) {
            scoreReset = this.eloScoring.get().resetToPardon(owner, request.section(), Map.of(
                    "punishmentIdentifier", active.getIdentifier(),
                    "revokedBy", request.source().toString()
            )).orElse(null);
        }

        SectionDTO.Response section = this.sectionService.find(request.section()).orElse(null);
        this.eventPublisher.publish("punishment.revoked", PunishmentEvents.revoked(active, section, request.source().toString()));

        return new PunishmentDTO.RevokeResponse(active.toDTO(), request.section(), scoreReset);
    }

    /**
     * Returns a paginated list of all punishment entries in the system.
     */
    public Page<PunishEntryDTO> findAll(Pageable pageable) {
        return this.punishmentRepository.findAll(pageable).map(PunishmentEntity::toDTO);
    }

    private static PunishmentDTO.Error error(ErrorKind kind, String message) {
        return new PunishmentDTO.Error(message, kind);
    }
}
