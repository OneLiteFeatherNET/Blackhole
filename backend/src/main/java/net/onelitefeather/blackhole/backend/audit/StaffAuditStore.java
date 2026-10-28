package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.UUID;

/** Storage of audit entries. Rows are only ever added, aged out by retention, or have a staff name cleared. */
public interface StaffAuditStore {

    /**
     * @throws RuntimeException when the row could not be stored
     */
    void insert(StaffAuditEntry entry);

    /**
     * The entries older than {@code beforeSeq} in newest-first order, optionally filtered.
     *
     * @return at most {@code limit} rows, newest first
     */
    List<Row> page(long beforeSeq, int limit, @Nullable String action, @Nullable UUID actorOid, @Nullable String targetRef);

    /**
     * Deletes up to {@code limit} entries that occurred before {@code cutoff} (epoch millis).
     *
     * @return how many were deleted
     */
    int deleteOccurredBefore(long cutoff, int limit);

    /** Clears the display name snapshot of every entry of {@code actorOid}; the oid stays. */
    void clearActorName(UUID actorOid);

    /** One stored entry with its ordering position, the cursor of a page. */
    record Row(long seq, StaffAuditEntry entry) {
    }
}
