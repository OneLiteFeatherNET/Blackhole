package net.onelitefeather.blackhole.backend.rollback;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persists rollback jobs and their frozen items. Writes nothing to punishments, slots or profiles. */
public interface RollbackJobStore {

    /**
     * Stores a new job in state {@code PREVIEW} together with its frozen items, all or nothing.
     *
     * @param job   the job to store
     * @param items the punishments the preview froze, each {@code PENDING}
     */
    void insertPreview(PreviewJob job, List<FrozenItem> items);

    /** A stored job as the execution needs it. */
    record Job(UUID id, String state, UUID requestedByOid, @Nullable UUID selectorOid, @Nullable String reason,
               @Nullable String idempotencyKey, long previewExpiresAt) {
    }

    /** A frozen item and where its revoke stands. */
    record Item(String identifier, String owner, String sectionKey, String state) {
    }

    /** @return the job, if it exists */
    Optional<Job> find(UUID id);

    /**
     * Moves a {@code PREVIEW} job to {@code RUNNING}, recording the reason. Only one caller can win.
     *
     * @return whether this call moved it
     */
    boolean markRunning(UUID id, String reason, String idempotencyKey, long now);

    /**
     * Takes the lease of one {@code RUNNING} job whose lease is free, expired or already held by {@code holder}.
     *
     * @param leaseUntil the lease end, epoch milliseconds
     * @return the job, if one was claimed
     */
    Optional<UUID> claimRunningJob(String holder, long now, long leaseUntil);

    /** Frees the lease, if {@code holder} still holds it. */
    void releaseLease(UUID id, String holder);

    /**
     * The next {@code PENDING} items in a stable order, at most {@code limit}. {@code FAILED} items are not
     * returned: they are retried only by an explicit execute call, never by the runner in a loop.
     */
    List<Item> nextItems(UUID id, int limit);

    /** Writes the state of one item. {@code reasonCode} is a short code, never a message. */
    void markItem(UUID id, String identifier, String state, @Nullable String reasonCode, long now);

    /** How many items are still {@code PENDING} or {@code FAILED}. */
    long countOpen(UUID id);

    /** Items per state, for progress. */
    Map<String, Long> countByState(UUID id);

    /** Moves a {@code RUNNING} job to {@code COMPLETED}. @return whether this call moved it */
    boolean markCompleted(UUID id, long now);

    /**
     * A new preview job.
     *
     * @param id             the job identifier
     * @param requestedByOid the verified {@code oid} of the admin who asked for the preview
     * @param selector       who is undone
     * @param windowFrom     the window start, epoch milliseconds
     * @param windowTo       the window end, epoch milliseconds
     * @param createdAt      when the preview was made, epoch milliseconds
     * @param previewExpiresAt when the preview stops being startable, epoch milliseconds
     */
    record PreviewJob(UUID id, UUID requestedByOid, RollbackSelector selector, long windowFrom, long windowTo, long createdAt,
                      long previewExpiresAt) {
    }

    /**
     * A punishment frozen into a job.
     *
     * @param identifier the punishment identifier
     * @param owner      the hashed owner
     * @param sectionKey the section key
     * @param kind       the punishment kind
     */
    record FrozenItem(String identifier, String owner, String sectionKey, String kind) {
    }
}
