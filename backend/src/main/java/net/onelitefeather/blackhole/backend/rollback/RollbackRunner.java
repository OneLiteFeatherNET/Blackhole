package net.onelitefeather.blackhole.backend.rollback;

import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentApplicationService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes started rollback jobs, one batch per {@link #tick()}. Every item goes through the manual revoke path
 * ({@link PunishmentApplicationService#revokeHeld}), so it gets the same case event, evidence marking, ELO
 * handling and {@code punishment.revoked} as a manual revoke. A tick never waits: the schedule calls it.
 *
 * <p>Idempotent and resumable. An item is revoked only while its slot still holds it, so an item processed twice
 * (after a crash between the revoke and the item write) becomes {@code SKIPPED} the second time. A runner that dies
 * keeps its lease until it expires on the injected clock; another runner then resumes at the first
 * {@code PENDING} item.</p>
 */
@Singleton
public class RollbackRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RollbackRunner.class);
    static final int BATCH_SIZE = 50;

    /**
     * The revoke of one frozen punishment. {@link PunishmentApplicationService#revokeHeld} is the production
     * implementation; the seam lets a test make one item fail.
     */
    @FunctionalInterface
    public interface ItemRevoke {
        Optional<PunishmentDTO.RevokeResponse> revoke(String owner, String section, String identifier, Attribution attribution,
                                                      Actor actor, UUID rollbackJobId, String rollbackReason);
    }

    private final RollbackJobStore jobs;
    private final ItemRevoke punishments;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final String holder;
    private final Duration lease;
    private final int batchSize;

    @Inject
    public RollbackRunner(RollbackJobStore jobs, PunishmentApplicationService punishments, DomainEventPublisher events, Clock clock,
                          @Value("${blackhole.rollback.lease:PT2M}") Duration lease) {
        this(jobs, punishments::revokeHeld, events, clock, UUID.randomUUID().toString(), lease, BATCH_SIZE);
    }

    /** For tests and for a runner with an explicit identity. */
    public RollbackRunner(RollbackJobStore jobs, ItemRevoke punishments, DomainEventPublisher events, Clock clock,
                          String holder, Duration lease, int batchSize) {
        this.jobs = jobs;
        this.punishments = punishments;
        this.events = events;
        this.clock = clock;
        this.holder = holder;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    /** The scheduled entry point. A failing tick is logged and tried again on the next schedule. */
    @Scheduled(fixedDelay = "${blackhole.rollback.tick-interval:5s}")
    public void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOGGER.warn("A rollback tick failed; the next one retries", e);
        }
    }

    /**
     * Claims one running job, revokes one batch of its pending items and completes it when none is left.
     *
     * @return whether a job was claimed (so there may be more work)
     */
    public boolean tick() {
        Optional<UUID> claimed = this.jobs.claimRunningJob(this.holder, this.clock.millis(), this.clock.millis() + this.lease.toMillis());
        if (claimed.isEmpty()) {
            return false;
        }
        UUID jobId = claimed.get();
        try {
            RollbackJobStore.Job job = this.jobs.find(jobId).orElseThrow();
            Attribution attribution = new Attribution(job.requestedByOid(), job.requestedByOid());
            Actor admin = new Actor(job.requestedByOid(), null, ActorKind.HUMAN, false, true);
            for (RollbackJobStore.Item item : this.jobs.nextItems(jobId, this.batchSize)) {
                processItem(job, item, attribution, admin);
            }
            if (this.jobs.countOpen(jobId) == 0 && this.jobs.markCompleted(jobId, this.clock.millis())) {
                publish(RollbackEvents.COMPLETED, RollbackEvents.completed(jobId, job.requestedByOid(), this.jobs.countByState(jobId)));
            }
            return true;
        } finally {
            this.jobs.releaseLease(jobId, this.holder);
        }
    }

    private void processItem(RollbackJobStore.Job job, RollbackJobStore.Item item, Attribution attribution, Actor admin) {
        try {
            Optional<PunishmentDTO.RevokeResponse> revoked = this.punishments.revoke(item.owner(), item.sectionKey(),
                    item.identifier(), attribution, admin, job.id(), job.reason());
            if (revoked.isPresent()) {
                this.jobs.markItem(job.id(), item.identifier(), "REVOKED", null, this.clock.millis());
            } else {
                this.jobs.markItem(job.id(), item.identifier(), "SKIPPED", "ALREADY_INACTIVE", this.clock.millis());
            }
        } catch (RuntimeException e) {
            // No message text in the item or the log line: the code says what failed.
            LOGGER.warn("Rollback item {} of job {} failed", item.identifier(), job.id(), e.getClass().getSimpleName());
            this.jobs.markItem(job.id(), item.identifier(), "FAILED", "REVOKE_FAILED", this.clock.millis());
        }
    }

    private void publish(String type, Map<String, Object> payload) {
        try {
            this.events.publish(type, payload);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish {}; the rollback itself is unaffected", type, e);
        }
    }
}
