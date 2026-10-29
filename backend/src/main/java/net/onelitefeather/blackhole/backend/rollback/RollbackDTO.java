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

    @Schema(name = "RollbackError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements RollbackDTO, ErrorResponse {
    }
}
