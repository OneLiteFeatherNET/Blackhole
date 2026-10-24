package net.onelitefeather.blackhole.backend.publicapi.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/** The player's copy of the appeal protocol: a file the controller serves as an attachment, or an error. */
@Schema(hidden = true)
@Serdeable
public sealed interface PublicProtocolDTO {

    /** The rendered file. */
    @Schema(hidden = true)
    @Serdeable
    @ReflectiveAccess
    record Download(@NonNull String fileName, @NonNull String contentType, byte @NonNull [] content) implements PublicProtocolDTO {
    }

    @Schema(hidden = true)
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String code, @Nullable String availableFrom, @NonNull PublicErrorKind kind) implements PublicProtocolDTO, PublicError {
    }
}
