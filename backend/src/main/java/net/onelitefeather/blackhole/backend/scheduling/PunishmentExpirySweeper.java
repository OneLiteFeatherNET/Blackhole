package net.onelitefeather.blackhole.backend.scheduling;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.profile.CacheInvalidationPublisher;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.utils.PunishmentExpiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Periodically moves expired active bans/mutes into a profile's history. This makes expiry an
 * active background process instead of something that only happens to be noticed the next
 * time a profile happens to be read (see {@code ProfileController.getById}).
 */
@Singleton
public class PunishmentExpirySweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentExpirySweeper.class);
    private static final int PAGE_SIZE = 200;

    private final PunishmentProfileRepository profileRepository;
    private final DomainEventPublisher eventPublisher;
    private final CacheInvalidationPublisher cacheInvalidationPublisher;

    public PunishmentExpirySweeper(
            PunishmentProfileRepository profileRepository,
            DomainEventPublisher eventPublisher,
            CacheInvalidationPublisher cacheInvalidationPublisher
    ) {
        this.profileRepository = profileRepository;
        this.eventPublisher = eventPublisher;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
    }

    @Scheduled(fixedDelay = "1m", initialDelay = "1m")
    void sweep() {
        int expiredCount = 0;
        Pageable pageable = Pageable.from(0, PAGE_SIZE);
        Page<PunishmentProfileEntity> page;
        do {
            page = this.profileRepository.findByActiveBanIsNotNullOrActiveChatBanIsNotNull(pageable);
            for (PunishmentProfileEntity profile : page.getContent()) {
                if (sweepProfile(profile)) {
                    expiredCount++;
                }
            }
            pageable = pageable.next();
        } while (page.getNumberOfElements() == PAGE_SIZE);

        if (expiredCount > 0) {
            LOGGER.info("Expired {} active punishment(s) during scheduled sweep", expiredCount);
        }
    }

    private boolean sweepProfile(PunishmentProfileEntity profile) {
        boolean changed = false;

        PunishmentEntity activeBan = profile.getActiveBan();
        if (activeBan != null && PunishmentExpiry.isExpired(activeBan)) {
            profile.getHistory().add(activeBan);
            profile.setActiveBan(null);
            changed = true;
            publishExpired(profile, activeBan);
        }

        PunishmentEntity activeChatBan = profile.getActiveChatBan();
        if (activeChatBan != null && PunishmentExpiry.isExpired(activeChatBan)) {
            profile.getHistory().add(activeChatBan);
            profile.setActiveChatBan(null);
            changed = true;
            publishExpired(profile, activeChatBan);
        }

        if (changed) {
            this.profileRepository.update(profile);
            this.cacheInvalidationPublisher.invalidate(profile.getOwner());
        }
        return changed;
    }

    private void publishExpired(PunishmentProfileEntity profile, PunishmentEntity expired) {
        this.eventPublisher.publish("punishment.expired", Map.of(
                "owner", profile.getOwner(),
                "punishmentIdentifier", expired.getIdentifier(),
                "type", expired.getType().toString()
        ));
    }
}
