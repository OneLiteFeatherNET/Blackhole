package net.onelitefeather.blackhole.backend.casetimeline.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/** Wire contract of the participant erasure of a case. Staff only; no variant carries a Discord id. */
@Schema(description = "Erasure of a conversation participant")
@Serdeable
public sealed interface CaseParticipantDTO {

    /** @param erased true when the participant was erased now, false when it had been erased before */
    @Schema(name = "CaseParticipantErased")
    @Serdeable
    @ReflectiveAccess
    record Erased(boolean erased) implements CaseParticipantDTO {
    }

    @Schema(name = "CaseParticipantError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull CaseErrorKind kind) implements CaseParticipantDTO, ErrorResponse {
    }
}
