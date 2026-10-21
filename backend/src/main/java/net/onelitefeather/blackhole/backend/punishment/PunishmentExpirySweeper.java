package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final ActivePunishmentExpirer expirer;

    public PunishmentExpirySweeper(PunishmentProfileRepository profileRepository, ActivePunishmentExpirer expirer) {
        this.profileRepository = profileRepository;
        this.expirer = expirer;
    }

    @Scheduled(
            fixedDelay = "${blackhole.punishment.expiry.sweep-interval:1m}",
            initialDelay = "${blackhole.punishment.expiry.sweep-interval:1m}"
    )
    void sweep() {
        int expiredCount = 0;
        Pageable pageable = Pageable.from(0, PAGE_SIZE);
        Page<PunishmentProfileEntity> page;
        do {
            page = this.profileRepository.findWithActivePunishments(pageable);
            for (PunishmentProfileEntity profile : page.getContent()) {
                if (this.expirer.expire(profile)) {
                    expiredCount++;
                }
            }
            pageable = pageable.next();
        } while (page.getNumberOfElements() == PAGE_SIZE);

        if (expiredCount > 0) {
            LOGGER.info("Expired active punishments of {} profile(s) during scheduled sweep", expiredCount);
        }
    }
}
