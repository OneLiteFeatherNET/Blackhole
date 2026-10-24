package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Keeps protocols complete and current. A run
 * <ol>
 *   <li>finishes the purges a redaction started (deletes the files of versions marked {@code PURGE_PENDING}),</li>
 *   <li>creates the protocol of every decided appeal that has none (the decision's live generation failed,
 *       or the appeal was decided before protocols existed), and</li>
 *   <li>regenerates the protocol of an appeal whose source events changed since its active version, once the
 *       conversation was quiet for {@code blackhole.protocol.settle-delay} on the injected clock.</li>
 * </ol>
 * A failed generation is logged, counted and simply tried again by the next run, so a redaction is never
 * undone and a decision never failed by it. Switched off by {@code blackhole.protocol.reconcile-interval: 0}.
 */
@Singleton
@Requires(property = "blackhole.protocol.reconcile-interval", notEquals = "0")
public class ProtocolReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProtocolReconciler.class);

    private final AppealProtocolService protocols;
    private final ProtocolStore store;
    private final int batchSize;

    public ProtocolReconciler(
            AppealProtocolService protocols,
            ProtocolStore store,
            @Value("${blackhole.protocol.reconcile-batch:200}") int batchSize
    ) {
        this.protocols = protocols;
        this.store = store;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelay = "${blackhole.protocol.reconcile-interval:PT5M}",
            initialDelay = "${blackhole.protocol.reconcile-interval:PT5M}"
    )
    void sweep() {
        try {
            int stored = reconcile();
            if (stored > 0) {
                LOGGER.info("Reconciled {} appeal protocol(s)", stored);
            }
        } catch (RuntimeException e) {
            // A scheduled task must not throw, or the scheduler may stop running it.
            LOGGER.error("Protocol reconciliation failed, trying again at the next interval", e);
        }
    }

    /**
     * One reconciliation pass.
     *
     * @return how many protocol versions were stored
     */
    public int reconcile() {
        this.protocols.purgePending();
        int stored = 0;
        for (UUID appealId : this.store.candidates(this.batchSize)) {
            if (this.protocols.generate(appealId, false).outcome() == AppealProtocolGenerator.Outcome.STORED) {
                stored++;
            }
        }
        return stored;
    }
}
