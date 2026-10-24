package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.publicapi.service.PlayerSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deletes player sessions past their idle or absolute limit, so a row never outlives its use. */
@Singleton
public class PlayerSessionSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSessionSweeper.class);

    private final PlayerSessionService sessions;

    public PlayerSessionSweeper(PlayerSessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(
            fixedDelay = "${blackhole.public.session.sweep-interval:PT1M}",
            initialDelay = "${blackhole.public.session.sweep-interval:PT1M}"
    )
    void sweep() {
        try {
            int removed = this.sessions.sweep();
            if (removed > 0) {
                LOGGER.info("Removed {} expired player session(s)", removed);
            }
        } catch (RuntimeException e) {
            // A scheduled task must not throw, or the scheduler may stop running it.
            LOGGER.error("Player session sweep failed, trying again at the next interval", e);
        }
    }
}
