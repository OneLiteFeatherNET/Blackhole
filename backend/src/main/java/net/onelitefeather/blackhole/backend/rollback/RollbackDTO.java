package net.onelitefeather.blackhole.backend.rollback;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.Map;
import java.util.UUID;

/**
 * Wire contract of {@code /admin/rollback}. The admin who asks is the caller of the verified token; no
 * request carries an actor.
 */
@Schema(description = "Rolling back the punishments one staff actor issued in a time window")
@Serdeable
public sealed interface RollbackDTO {

    /**
     * Preview a rollback. Exactly one selector: a staff member's {@code oid}, or the Minecraft UUID of an
     * unlinked in-game account.
     *
     * @param staffOid      the HUMAN staff actor, or null
     * @param minecraftUuid the unlinked in-game account, or null
     * @param windowFrom    the window start, epoch milliseconds, inclusive
     * @param windowTo      the window end, epoch milliseconds, inclusive
     */
    @Schema(name = "RollbackPreviewRequest")
    @Serdeable
    @ReflectiveAccess
    record PreviewRequest(
            @Nullable UUID staffOid,
            @Nullable UUID minecraftUuid,
            long windowFrom,
            long windowTo
    ) implements RollbackDTO {
    }

    /**
     * The frozen preview. Nothing has been revoked; the job is {@code PREVIEW} until it is started or it expires.
     *
     * @param jobId            the job identifier
     * @param state            {@code PREVIEW}
     * @param previewExpiresAt when the preview stops being startable, epoch milliseconds
     * @param revocable        how many punishments would be revoked
     * @param alreadyInactive  how many matching punishments are already lifted or ended and are not revoked
     * @param players          how many distinct (hashed) players the revocable punishments belong to
     * @param byKind           the revocable count per punishment kind
     * @param bySection        the revocable count per section key
     */
    @Schema(name = "RollbackPreviewResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull UUID jobId,
            @NonNull String state,
            long previewExpiresAt,
            int revocable,
            int alreadyInactive,
            int players,
            @NonNull Map<String, Integer> byKind,
            @NonNull Map<String, Integer> bySection
    ) implements RollbackDTO {
    }

    /**
     * The rollback was started, or the same start was repeated.
     *
     * @param jobId    the job identifier
     * @param state    {@code RUNNING}, or the job's current state for a repeat
     * @param repeated whether the {@code Idempotency-Key} was already used: nothing was written
     */
    @Schema(name = "RollbackStarted")
    @Serdeable
    @ReflectiveAccess
    record Started(@NonNull UUID jobId, @NonNull String state, boolean repeated) implements RollbackDTO {
    }

    /**
     * Confirm a previewed rollback. The {@code Idempotency-Key} header (not a field) makes a repeat answer the same job.
     *
     * @param reason      the justification, 1 to 500 characters, shown in the audit trail
     * @param suspendLink whether to remove the Minecraft link of the staff member before the items run
     */
    @Schema(name = "RollbackExecuteRequest")
    @Serdeable
    @ReflectiveAccess
    record ExecuteRequest(@NonNull String reason, boolean suspendLink) implements RollbackDTO {
    }

    /**
     * How a job is going: its state and how many items are in each state.
     *
     * @param jobId the job identifier
     * @param state {@code PREVIEW}, {@code RUNNING}, {@code COMPLETED} or {@code CANCELLED}
     * @param items the item count per state: {@code PENDING}, {@code REVOKED}, {@code SKIPPED}, {@code FAILED}
     */
    @Schema(name = "RollbackProgress")
    @Serdeable
    @ReflectiveAccess
    record Progress(@NonNull UUID jobId, @NonNull String state, @NonNull Map<String, Long> items) implements RollbackDTO {
    }

    /**
     * The job was cancelled. Items already revoked stay revoked; the pending ones are not processed.
     *
     * @param jobId the job identifier
     * @param state {@code CANCELLED}
     */
    @Schema(name = "RollbackCancelled")
    @Serdeable
    @ReflectiveAccess
    record Cancelled(@NonNull UUID jobId, @NonNull String state) implements RollbackDTO {
    }

    @Schema(name = "RollbackError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements RollbackDTO, ErrorResponse {
    }
}
