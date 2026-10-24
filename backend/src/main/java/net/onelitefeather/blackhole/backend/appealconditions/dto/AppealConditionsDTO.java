package net.onelitefeather.blackhole.backend.appealconditions.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/** Wire contract of the staff routes for the appeal conditions. */
@Schema(description = "The appeal conditions")
@Serdeable
public sealed interface AppealConditionsDTO {

    /**
     * Saves a new version.
     *
     * @param text 1 to 4000 characters, plain text; a blank line separates paragraphs and a line starting with "- " is a bullet
     */
    @Schema(name = "AppealConditionsRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(@Nullable String text) implements AppealConditionsDTO {
    }

    /**
     * One version.
     *
     * @param createdAt an ISO instant
     */
    @Schema(name = "AppealConditions")
    @Serdeable
    @ReflectiveAccess
    record Response(int version, @NonNull String text, @NonNull String createdAt) implements AppealConditionsDTO {
    }

    @Schema(name = "AppealConditionsError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements AppealConditionsDTO, ErrorResponse {
    }
}
