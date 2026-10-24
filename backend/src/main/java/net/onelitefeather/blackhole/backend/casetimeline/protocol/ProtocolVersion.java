package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/**
 * One stored version of an appeal's protocol, as the index table holds it.
 *
 * @param evidenceId the {@code TRANSCRIPT} evidence item holding the files; {@code null} once they were deleted
 * @param sourceDigest SHA-256 of the source events (see {@link ProtocolModel#digest})
 * @param firstSeq   the lowest {@code seq} of the source events, {@code null} for an empty conversation
 * @param lastSeq    the highest {@code seq} of the source events
 */
public record ProtocolVersion(
        UUID identifier,
        String punishmentIdentifier,
        UUID appealId,
        int version,
        @Nullable UUID evidenceId,
        String sourceDigest,
        int entryCount,
        @Nullable Long firstSeq,
        @Nullable Long lastSeq,
        Status status,
        long createdAt
) {

    /** The state of a version. Stored as text. */
    public enum Status {

        /** The latest version; at most one per appeal. */
        ACTIVE,

        /** An earlier version, kept so staff can see what the record said before. */
        SUPERSEDED,

        /** A redaction made it unreadable at once; its files are about to be deleted. */
        PURGE_PENDING,

        /** Its files were deleted. The row stays so a version number is never reused. */
        PURGED;

        /** Whether staff may read the version. */
        public boolean readable() {
            return this == ACTIVE || this == SUPERSEDED;
        }
    }
}
