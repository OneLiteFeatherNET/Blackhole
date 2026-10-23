package net.onelitefeather.blackhole.backend.security.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.UUID;

/**
 * Wire contract of {@code /admin/staff-actor}: erasing the personal data kept about a staff principal.
 */
@Schema(description = "Staff identity data kept for accountability")
@Serdeable
public sealed interface StaffActorDTO {

    /** The principal whose display name was cleared; the {@code oid} and all history stay. */
    @Schema(name = "StaffActorAnonymizeResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull UUID oid) implements StaffActorDTO {
    }

    @Schema(name = "StaffActorError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements StaffActorDTO, ErrorResponse {
    }
}
