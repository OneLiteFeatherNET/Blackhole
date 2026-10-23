package net.onelitefeather.blackhole.backend.appeal.dto;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;
import java.util.UUID;

/**
 * An appeal as it appears in responses.
 *
 * @param evidenceCount how many valid evidence items the appealed punishment has: the only thing about
 *                      evidence an appeal response carries, never its content, references or files
 */
@Serdeable
@ReflectiveAccess
public record AppealDTO(
        @NonNull UUID identifier,
        @NonNull String punishmentIdentifier,
        @NonNull String appellantHash,
        @NonNull String statement,
        @NonNull AppealStatus status,
        @NonNull Map<String, Object> eligibilityCheckResult,
        @Nullable UUID decidedBy,
        @Nullable UUID recordedByOid,
        boolean verified,
        @Nullable String decisionNote,
        long createdAt,
        long updatedAt,
        long evidenceCount,
        @NonNull Map<String, Object> metaData
) {

    /** This appeal with the number of valid evidence items of its punishment filled in. */
    public AppealDTO withEvidenceCount(long count) {
        return new AppealDTO(this.identifier, this.punishmentIdentifier, this.appellantHash, this.statement, this.status,
                this.eligibilityCheckResult, this.decidedBy, this.recordedByOid, this.verified, this.decisionNote, this.createdAt,
                this.updatedAt, count, this.metaData);
    }
}
