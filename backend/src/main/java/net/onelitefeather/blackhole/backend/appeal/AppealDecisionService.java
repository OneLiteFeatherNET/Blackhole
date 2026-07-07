package net.onelitefeather.blackhole.backend.appeal;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.cache.CacheInvalidationPublisher;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileId;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.dto.AppealStatus;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;

import java.util.UUID;

/**
 * The mechanics of actually granting an appeal - lifting a punishment early or shortening it.
 * {@code PunishmentApplicationService} only knows how to apply new punishments; reversing one
 * that's already active is a distinct operation this service owns instead.
 */
@Singleton
public class AppealDecisionService {

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final CacheInvalidationPublisher cacheInvalidationPublisher;

    public AppealDecisionService(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            CacheInvalidationPublisher cacheInvalidationPublisher
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
    }

    /**
     * @param punishment      the punishment being appealed
     * @param tenantId        the tenant it belongs to
     * @param owner           the appellant's hashed owner (also the punishment's owner)
     * @param decision        {@code GRANTED_FULL_LIFT}, {@code GRANTED_DURATION_REDUCTION}, or {@code DENIED}
     * @param newExpirationAt required for {@code GRANTED_DURATION_REDUCTION}, ignored otherwise
     * @return whether the decision was applied, or {@code PUNISHMENT_NOT_ACTIVE} if the
     * punishment already expired/was lifted naturally in the meantime - nothing left to grant
     */
    public DecisionOutcome applyDecision(PunishmentEntity punishment, UUID tenantId, String owner, AppealStatus decision, Long newExpirationAt) {
        if (decision == AppealStatus.DENIED) {
            return DecisionOutcome.APPLIED;
        }

        PunishmentProfileEntity profile = this.profileRepository.findById(new PunishmentProfileId(tenantId, owner)).orElse(null);
        if (profile == null) {
            return DecisionOutcome.PUNISHMENT_NOT_ACTIVE;
        }

        boolean isActiveBan = profile.getActiveBan() != null && profile.getActiveBan().getIdentifier().equals(punishment.getIdentifier());
        boolean isActiveChatBan = !isActiveBan && profile.getActiveChatBan() != null
                && profile.getActiveChatBan().getIdentifier().equals(punishment.getIdentifier());
        if (!isActiveBan && !isActiveChatBan) {
            return DecisionOutcome.PUNISHMENT_NOT_ACTIVE;
        }

        if (decision == AppealStatus.GRANTED_FULL_LIFT) {
            if (isActiveBan) {
                profile.getHistory().add(profile.getActiveBan());
                profile.setActiveBan(null);
            } else {
                profile.getHistory().add(profile.getActiveChatBan());
                profile.setActiveChatBan(null);
            }
            this.profileRepository.update(profile);
        } else if (decision == AppealStatus.GRANTED_DURATION_REDUCTION) {
            punishment.getMetaData().put(Expirable.META_DATA_KEY_EXPIRATION_DATE, newExpirationAt);
            punishment.getMetaData().put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());
            this.punishmentRepository.update(punishment);
        }

        this.cacheInvalidationPublisher.invalidate(tenantId, owner);
        return DecisionOutcome.APPLIED;
    }
}
