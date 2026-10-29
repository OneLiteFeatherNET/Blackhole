package net.onelitefeather.blackhole.backend.rollback;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Starts a previewed rollback: the reason and the idempotency key are checked, the job moves from {@code PREVIEW}
 * to {@code RUNNING}, and the runner takes it from there. The check that the job is still startable is part of the
 * write itself, so two starts cannot both win.
 */
@Singleton
public class RollbackExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RollbackExecutionService.class);
    static final int MAX_REASON = 500;
    static final int MAX_KEY = 64;

    private final RollbackJobStore jobs;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final StaffLinkService staffLinks;

    @Inject
    public RollbackExecutionService(RollbackJobStore jobs, DomainEventPublisher events, Clock clock, StaffLinkService staffLinks) {
        this.jobs = jobs;
        this.events = events;
        this.clock = clock;
        this.staffLinks = staffLinks;
    }

    /** A start without the optional link suspension. */
    public RollbackDTO start(UUID jobId, @Nullable String reason, @Nullable String idempotencyKey) {
        return start(jobId, reason, idempotencyKey, false);
    }

    /**
     * @param jobId          the previewed job
     * @param reason         the admin's justification, 1 to 500 characters
     * @param idempotencyKey the {@code Idempotency-Key} header; a repeat with the same key answers the same job
     * @param suspendLink    whether to remove the Minecraft link of the selected staff member before the items run
     * @return {@link RollbackDTO.Started} or a {@link RollbackDTO.Error}
     */
    public RollbackDTO start(UUID jobId, @Nullable String reason, @Nullable String idempotencyKey, boolean suspendLink) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY) {
            return error(ErrorKind.VALIDATION, "an Idempotency-Key of 1 to 64 characters is required");
        }
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON) {
            return error(ErrorKind.VALIDATION, "the reason must be 1 to 500 characters");
        }
        Optional<RollbackJobStore.Job> found = this.jobs.find(jobId);
        if (found.isEmpty()) {
            return error(ErrorKind.NOT_FOUND, "there is no such rollback");
        }
        RollbackJobStore.Job job = found.get();
        if (idempotencyKey.equals(job.idempotencyKey())) {
            return new RollbackDTO.Started(jobId, job.state(), true);
        }
        if (!"PREVIEW".equals(job.state())) {
            return error(ErrorKind.CONFLICT, "the rollback was already started");
        }
        long now = this.clock.millis();
        if (now >= job.previewExpiresAt()) {
            return error(ErrorKind.CONFLICT, "the preview expired; preview again");
        }
        boolean moved;
        try {
            moved = this.jobs.markRunning(jobId, reason, idempotencyKey, now);
        } catch (IllegalStateException e) {
            // The idempotency key is unique per job: another job already used it.
            return error(ErrorKind.CONFLICT, "the Idempotency-Key was already used for another rollback");
        }
        if (!moved) {
            return error(ErrorKind.CONFLICT, "the rollback was already started");
        }
        if (suspendLink && job.selectorOid() != null) {
            suspendLink(job);
        }
        publish(jobId, job.requestedByOid());
        return new RollbackDTO.Started(jobId, "RUNNING", false);
    }

    /**
     * Removes the selected staff member's Minecraft link before the first item runs. A failure is logged and does not
     * stop the rollback: the punishments are still undone, and the link can be removed by hand.
     */
    private void suspendLink(RollbackJobStore.Job job) {
        try {
            this.staffLinks.removeForRollback(job.selectorOid(), job.requestedByOid());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not remove the staff link of rollback {}; the rollback continues", job.id(), e);
        }
    }

    private void publish(UUID jobId, UUID requestedByOid) {
        Map<String, Long> counts = this.jobs.countByState(jobId);
        long frozen = counts.values().stream().mapToLong(Long::longValue).sum();
        try {
            this.events.publish(RollbackEvents.STARTED, RollbackEvents.started(jobId, requestedByOid, frozen));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish {}; the rollback is started regardless", RollbackEvents.STARTED, e);
        }
    }

    private static RollbackDTO.Error error(ErrorKind kind, String message) {
        return new RollbackDTO.Error(message, kind);
    }
}
