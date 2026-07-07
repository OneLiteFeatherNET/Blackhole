package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.EloProfileEntity;
import net.onelitefeather.blackhole.backend.database.repository.EloProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nightly counterpart to {@link EloService}'s lazy, on-write decay reconciliation - without
 * this, a player who stops playing entirely (so nothing ever triggers {@code applyDelta} again)
 * would never recover, even years later.
 */
@Singleton
public class EloDecaySweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(EloDecaySweeper.class);
    private static final int PAGE_SIZE = 200;

    private final EloProfileRepository profileRepository;
    private final EloService eloService;

    public EloDecaySweeper(EloProfileRepository profileRepository, EloService eloService) {
        this.profileRepository = profileRepository;
        this.eloService = eloService;
    }

    @Scheduled(cron = "${blackhole.elo.decay.sweep-cron:0 0 3 * * *}")
    void sweep() {
        int updatedCount = 0;
        Pageable pageable = Pageable.from(0, PAGE_SIZE);
        Page<EloProfileEntity> page;
        do {
            page = this.profileRepository.findAll(pageable);
            for (EloProfileEntity profile : page.getContent()) {
                if (this.eloService.reconcileDecayForProfile(profile)) {
                    updatedCount++;
                }
            }
            pageable = pageable.next();
        } while (page.getNumberOfElements() == PAGE_SIZE);

        if (updatedCount > 0) {
            LOGGER.info("Applied ELO decay to {} profile(s) during nightly sweep", updatedCount);
        }
    }
}
