package net.onelitefeather.blackhole.backend.punishment;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheInvalidationPublisher;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileId;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
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
 * publishing rather than duplicating it.
 */
@Singleton
public class PunishmentApplicationService {

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final PunishmentTemplateRepository templateRepository;
    private final DomainEventPublisher eventPublisher;
    private final CacheInvalidationPublisher cacheInvalidationPublisher;

    public PunishmentApplicationService(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            PunishmentTemplateRepository templateRepository,
            DomainEventPublisher eventPublisher,
            CacheInvalidationPublisher cacheInvalidationPublisher
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
        this.eventPublisher = eventPublisher;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
    }

    /**
     * Applies {@code templateId} to {@code owner}'s profile, creating the profile if this is
     * their first ever punishment (a report about a first-time offender must still be
     * actionable). Returns empty if the template doesn't exist or belongs to a different tenant.
     *
     * @param tenantId   the tenant the punishment belongs to
     * @param owner      the SHA-512-hashed owner the punishment applies to
     * @param templateId the template to apply
     * @param source     the staff/system identity applying the punishment
     * @return the updated profile, or empty if the template is missing/cross-tenant
     */
    public Optional<PunishmentProfileEntity> apply(UUID tenantId, String owner, UUID templateId, UUID source) {
        return apply(tenantId, owner, templateId, source, Map.of());
    }

    /**
     * Same as {@link #apply(UUID, String, UUID, UUID)}, but merges {@code extraMetaData} into
     * the created punishment's metadata - e.g. so an ELO-triggered ban can record exactly which
     * signal type crossed the threshold, for a later appeal's eligibility checklist to read
     * without having to reverse-engineer it from timestamps.
     */
    public Optional<PunishmentProfileEntity> apply(UUID tenantId, String owner, UUID templateId, UUID source, Map<String, Object> extraMetaData) {
        PunishmentTemplateEntity template = this.templateRepository.findById(templateId).orElse(null);
        if (template == null || !tenantId.equals(template.getTenantId())) {
            return Optional.empty();
        }

        PunishmentProfileId profileId = new PunishmentProfileId(tenantId, owner);
        PunishmentProfileEntity profile = this.profileRepository.findById(profileId).orElse(null);
        boolean isNewProfile = profile == null;
        if (isNewProfile) {
            profile = new PunishmentProfileEntity(tenantId, owner, null, null, new ArrayList<>(), new HashMap<>());
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

        PunishmentEntity punishment = new PunishmentEntity(IdGenerator.generateId(), tenantId, source, template.getType(), null, template, metadata);
        PunishmentEntity savedPunishment = this.punishmentRepository.save(punishment);

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

        this.cacheInvalidationPublisher.invalidate(tenantId, owner);

        if (isNewProfile) {
            this.eventPublisher.publish("profile.created", Map.of("tenantId", tenantId.toString(), "owner", owner));
        }
        this.eventPublisher.publish("punishment.created", Map.of(
                "tenantId", tenantId.toString(),
                "owner", owner,
                "punishmentIdentifier", savedPunishment.getIdentifier(),
                "templateIdentifier", templateId.toString(),
                "type", template.getType().toString()
        ));

        return Optional.of(savedProfile);
    }
}
