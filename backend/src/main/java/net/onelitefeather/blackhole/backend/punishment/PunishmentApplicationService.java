package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.context.BeanProvider;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.dto.EloReasonCode;
import net.onelitefeather.blackhole.backend.dto.EloTrack;
import net.onelitefeather.blackhole.backend.elo.EloService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.profile.CacheInvalidationPublisher;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.phoca.metadata.Durationable;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies a punishment template to a profile. Shared between {@code PunishmentEntityController}
 * (direct staff/service punishment) and the Phase-3 report resolution flow, so both go through
 * identical template lookup, expiry calculation, history rotation, cache invalidation and event
 * publishing rather than duplicating it. Being the one chokepoint every punishment goes through
 * is also why a template's {@code eloDelta} is applied here rather than in each caller - it's
 * the only place guaranteed to see every temp-ban regardless of who/what issued it.
 */
@Singleton
public class PunishmentApplicationService {

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final PunishmentTemplateRepository templateRepository;
    private final DomainEventPublisher eventPublisher;
    private final CacheInvalidationPublisher cacheInvalidationPublisher;
    private final BeanProvider<EloService> eloServiceProvider;

    /**
     * @param eloServiceProvider lazy - {@link EloService} itself depends on this service (to
     *                           apply auto-bans), so a direct constructor-injected {@link EloService}
     *                           would form a circular dependency. {@link BeanProvider} defers
     *                           resolution until first actually used, breaking the cycle.
     */
    public PunishmentApplicationService(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            PunishmentTemplateRepository templateRepository,
            DomainEventPublisher eventPublisher,
            CacheInvalidationPublisher cacheInvalidationPublisher,
            BeanProvider<EloService> eloServiceProvider
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
        this.eventPublisher = eventPublisher;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
        this.eloServiceProvider = eloServiceProvider;
    }

    /**
     * Applies {@code templateId} to {@code owner}'s profile, creating the profile if this is
     * their first ever punishment (a report about a first-time offender must still be
     * actionable). Returns empty if the template doesn't exist.
     *
     * @param owner      the SHA-512-hashed owner the punishment applies to
     * @param templateId the template to apply
     * @param source     the staff/system identity applying the punishment
     * @return the updated profile, or empty if the template is missing
     */
    public Optional<PunishmentProfileEntity> apply(String owner, UUID templateId, UUID source) {
        return apply(owner, templateId, source, Map.of());
    }

    /**
     * Same as {@link #apply(String, UUID, UUID)}, but merges {@code extraMetaData} into
     * the created punishment's metadata - e.g. so an ELO-triggered ban can record exactly which
     * signal type crossed the threshold, for a later appeal's eligibility checklist to read
     * without having to reverse-engineer it from timestamps.
     */
    public Optional<PunishmentProfileEntity> apply(String owner, UUID templateId, UUID source, Map<String, Object> extraMetaData) {
        PunishmentTemplateEntity template = this.templateRepository.findById(templateId).orElse(null);
        if (template == null) {
            return Optional.empty();
        }

        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        boolean isNewProfile = profile == null;
        if (isNewProfile) {
            profile = new PunishmentProfileEntity(owner, null, null, new ArrayList<>(), new HashMap<>());
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.putAll(extraMetaData);
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, System.currentTimeMillis());
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());
        if (template.getMetaData().containsKey(Durationable.META_DATA_KEY_DURATION)) {
            String durationRaw = (String) template.getMetaData().get(Durationable.META_DATA_KEY_DURATION);
            Duration duration = Duration.parse(durationRaw);
            metadata.put(Expirable.META_DATA_KEY_EXPIRATION_DATE, System.currentTimeMillis() + duration.toMillis());
        }

        PunishmentEntity punishment = new PunishmentEntity(IdGenerator.generateId(), source, template.getType(), null, template, metadata);
        PunishmentEntity savedPunishment = this.punishmentRepository.save(punishment);

        if (template.getEloDelta() != 0 && !EloService.SYSTEM_ELO_SOURCE.equals(source)) {
            EloTrack track = template.getType() == PunishType.CHAT ? EloTrack.CHAT : EloTrack.GAMEPLAY;
            this.eloServiceProvider.get().applyDelta(owner, track, template.getEloDelta(), EloReasonCode.PUNISHMENT_APPLIED, null, Map.of(
                    "punishmentIdentifier", savedPunishment.getIdentifier(),
                    "templateIdentifier", templateId.toString()
            ));
        }

        if (profile.getActiveBan() != null) {
            profile.getHistory().add(profile.getActiveBan());
            profile.setActiveBan(null);
        }
        if (profile.getActiveChatBan() != null) {
            profile.getHistory().add(profile.getActiveChatBan());
            profile.setActiveChatBan(null);
        }

        switch (template.getType()) {
            case CHAT -> profile.setActiveChatBan(savedPunishment);
            case SERVER, NETWORK -> profile.setActiveBan(savedPunishment);
        }

        PunishmentProfileEntity savedProfile = isNewProfile ? this.profileRepository.save(profile) : this.profileRepository.update(profile);

        this.cacheInvalidationPublisher.invalidate(owner);

        if (isNewProfile) {
            this.eventPublisher.publish("profile.created", Map.of("owner", owner));
        }
        Map<String, Object> punishmentCreatedPayload = new HashMap<>();
        punishmentCreatedPayload.put("owner", owner);
        punishmentCreatedPayload.put("punishmentIdentifier", savedPunishment.getIdentifier());
        punishmentCreatedPayload.put("templateIdentifier", templateId.toString());
        punishmentCreatedPayload.put("type", template.getType().toString());
        if (metadata.containsKey(Expirable.META_DATA_KEY_EXPIRATION_DATE)) {
            punishmentCreatedPayload.put("expiresAt", metadata.get(Expirable.META_DATA_KEY_EXPIRATION_DATE));
        }
        this.eventPublisher.publish("punishment.created", punishmentCreatedPayload);

        return Optional.of(savedProfile);
    }

    /**
     * Revokes {@code owner}'s active ban (SERVER or NETWORK), if any.
     *
     * @return the updated profile, or empty if the profile doesn't exist or has no active ban
     */
    public Optional<PunishmentProfileEntity> revokeBan(String owner, UUID revokedBy) {
        return revoke(owner, revokedBy, true);
    }

    /**
     * Revokes {@code owner}'s active chat ban, if any.
     *
     * @return the updated profile, or empty if the profile doesn't exist or has no active mute
     */
    public Optional<PunishmentProfileEntity> revokeMute(String owner, UUID revokedBy) {
        return revoke(owner, revokedBy, false);
    }

    /**
     * A deliberately independent 4th implementation of the "move active punishment into history"
     * rotation already present in {@link #apply}, {@code PunishmentExpirySweeper.sweepProfile}
     * and {@code AppealDecisionService.applyDecision} - not a refactor of those, to avoid
     * regression risk in already-working code.
     */
    private Optional<PunishmentProfileEntity> revoke(String owner, UUID revokedBy, boolean banSlot) {
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }

        PunishmentEntity active = banSlot ? profile.getActiveBan() : profile.getActiveChatBan();
        if (active == null) {
            return Optional.empty();
        }

        active.getMetaData().put("revokedBy", revokedBy.toString());
        active.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());
        this.punishmentRepository.update(active);

        profile.getHistory().add(active);
        if (banSlot) {
            profile.setActiveBan(null);
        } else {
            profile.setActiveChatBan(null);
        }
        PunishmentProfileEntity savedProfile = this.profileRepository.update(profile);

        this.cacheInvalidationPublisher.invalidate(owner);
        this.eventPublisher.publish("punishment.revoked", Map.of(
                "owner", owner,
                "punishmentIdentifier", active.getIdentifier(),
                "type", active.getType().toString(),
                "revokedBy", revokedBy.toString()
        ));

        return Optional.of(savedProfile);
    }
}
