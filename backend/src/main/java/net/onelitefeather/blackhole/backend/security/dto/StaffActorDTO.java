package net.onelitefeather.blackhole.backend.security.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import net.onelitefeather.blackhole.backend.security.ActorKind;

import io.micronaut.core.annotation.Nullable;
import java.util.List;
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

    /**
     * One known staff principal.
     *
     * @param oid         the Entra object id
     * @param kind        a signed-in person or an application
     * @param displayName the name from the sign-in; absent once anonymized
     * @param firstSeenAt epoch millis of the first verified request
     * @param lastSeenAt  epoch millis of the latest verified request
     */
    @Schema(name = "StaffActorItem")
    @Serdeable
    @ReflectiveAccess
    record Item(
            @NonNull UUID oid, @NonNull ActorKind kind, @Nullable String displayName, long firstSeenAt, long lastSeenAt
    ) {
    }

    /** Every known staff principal, most recently seen first. */
    @Schema(name = "StaffActorListResponse")
    @Serdeable
    @ReflectiveAccess
    record ListResponse(@NonNull List<Item> items) implements StaffActorDTO {
    }

    @Schema(name = "StaffActorError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements StaffActorDTO, ErrorResponse {
    }
}
