package net.onelitefeather.blackhole.backend.intel.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/**
 * Wire contract of the per-player allow-list ({@code /intel/allow-list}).
 */
@Schema(description = "A per-player exemption from the VPN and hosting policy")
@Serdeable
public sealed interface AllowListDTO {

    /**
     * Exempts one player. A moderator may give a number of days from 1 to 90; only an admin may leave the duration
     * out, which makes the exemption permanent.
     *
     * @param owner        the player's owner hash, 128 hex characters
     * @param reason       why the exemption is given, 1 to 200 characters
     * @param durationDays the days the exemption lasts, or null for a permanent one
     */
    @Schema(name = "AllowListCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(@NonNull String owner, @NonNull String reason, @Nullable Integer durationDays)
            implements AllowListDTO {
    }

    /**
     * One stored exemption. It holds no name and no address.
     *
     * @param expiresAt epoch milliseconds, or null when permanent
     */
    @Schema(name = "AllowListResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull String owner, @NonNull String reason, @NonNull String actorOid, @Nullable Long expiresAt,
                    long createdAt) implements AllowListDTO {
    }

    @Schema(name = "AllowListError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements AllowListDTO, ErrorResponse {
    }
}
