package net.onelitefeather.blackhole.backend.warning.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code /warn}. A warning is a punishment row of kind {@code WARN} that the player sees in game and
 * confirms once; the staff member behind it is the one who applied the template.
 */
@Schema(description = "Warnings: told to a player in game, confirmed by the player once")
@Serdeable
public sealed interface WarningDTO {

    /**
     * One warning and how far it got.
     *
     * @param id             the warning's Ban ID
     * @param owner          the hashed player
     * @param reason         the template's reason, shown to the player; no UUID, IP address or name beside the actor
     * @param actor          the display name of the staff member, if known
     * @param createdAt      when the warning was given, epoch milliseconds
     * @param acknowledgedAt when the player confirmed it, epoch milliseconds; {@code null} while pending
     */
    @Schema(name = "WarningResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull String id,
            @NonNull String owner,
            @NonNull String reason,
            @Nullable String actor,
            long createdAt,
            @Nullable Long acknowledgedAt
    ) implements WarningDTO {
    }

    /**
     * The warnings of one player that nobody has confirmed yet, oldest first. At most the requested limit: a player
     * with more sees the rest on the next login.
     *
     * @param items the pending warnings
     */
    @Schema(name = "WarningPending")
    @Serdeable
    @ReflectiveAccess
    record Pending(@NonNull List<Response> items) implements WarningDTO {
    }

    /**
     * The player confirms a warning. Only the proxy may send it, on behalf of the player who clicked.
     *
     * @param owner the hashed player the warning belongs to; a mismatch is refused like an unknown warning
     */
    @Schema(name = "WarningAcknowledgeRequest")
    @Serdeable
    @ReflectiveAccess
    record AcknowledgeRequest(@NonNull String owner) implements WarningDTO {
    }

    /**
     * The confirmation is stored. A repeat answers the same, with the first time.
     *
     * @param acknowledgedAt when the player first confirmed the warning, epoch milliseconds
     */
    @Schema(name = "WarningAcknowledged")
    @Serdeable
    @ReflectiveAccess
    record Acknowledged(long acknowledgedAt) implements WarningDTO {
    }

    @Schema(name = "WarningError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements WarningDTO, ErrorResponse {
    }
}
