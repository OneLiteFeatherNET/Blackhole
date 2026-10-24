package net.onelitefeather.blackhole.backend.banid;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The operator's trigger for {@link BanIdReissueAll}: runs it once, in the background, after the
 * application started, and only when {@code blackhole.banid.reissue-all} is {@code true}
 * ({@code BLACKHOLE_BANID_REISSUE_ALL=true}, or the argument {@code --blackhole.banid.reissue-all=true}).
 * Off by default, so a key rotation never reissues anything by itself. Logs counts only.
 */
@Singleton
@Requires(property = "blackhole.banid.reissue-all", value = "true")
public class BanIdReissueAllRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BanIdReissueAllRunner.class);

    private final BanIdReissueAll job;

    public BanIdReissueAllRunner(BanIdReissueAll job) {
        this.job = job;
    }

    @EventListener
    void onStartup(StartupEvent event) {
        LOGGER.warn("blackhole.banid.reissue-all is on: reissuing every Ban ID the current key cannot read. Switch it off again once the run has finished.");
        Thread.ofVirtual().name("banid-reissue-all").start(() -> {
            try {
                this.job.run();
            } catch (RuntimeException e) {
                LOGGER.error("The Ban ID key rotation reissue stopped ({}); start the application with it again to resume", e.getClass().getSimpleName());
            }
        });
    }
}
