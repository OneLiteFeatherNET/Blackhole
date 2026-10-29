package net.onelitefeather.blackhole.backend.rollback;

import java.util.List;
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
