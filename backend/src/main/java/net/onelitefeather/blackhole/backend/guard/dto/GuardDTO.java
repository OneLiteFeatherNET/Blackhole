package net.onelitefeather.blackhole.backend.guard.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/** Wire contract of the join guard level routes: the state the panel shows, and the manual level a staff member sets. */
@Schema(description = "The join guard level, its manual and unattended sources, and the one in force")
@Serdeable
public sealed interface GuardDTO {

    /** @param level OFF, STRICT or LOCKDOWN */
    @Schema(name = "GuardLevelRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(@NonNull String level) implements GuardDTO {
    }

    /**
     * @param manualLevel    the level a staff member set
     * @param automaticLevel the level the unattended evaluator set
     * @param effectiveLevel the stricter of the two, which is in force
     * @param updatedAt      when the state last changed, epoch milliseconds
     */
    @Schema(name = "GuardStateResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull String manualLevel, @NonNull String automaticLevel, @NonNull String effectiveLevel,
                    long updatedAt) implements GuardDTO {
    }

    @Schema(name = "GuardError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements GuardDTO, ErrorResponse {
    }
}
