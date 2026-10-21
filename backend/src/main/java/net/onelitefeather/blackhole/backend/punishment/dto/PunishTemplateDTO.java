package net.onelitefeather.blackhole.backend.punishment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire contract of {@code /template}. A template is always temporary: it belongs to a section and
 * lists ordered escalation steps; the system alone issues permanent punishments.
 */
@Schema(description = "A punishment template: a reason, a section and an escalation ladder of steps")
@Serdeable
public sealed interface PunishTemplateDTO {

    /**
     * One escalation step.
     *
     * @param duration ISO-8601 duration, greater than zero
     * @param eloCost  ELO deducted from the section when this step is applied, zero or more
     */
    @Schema(name = "PunishTemplateStep")
    @Serdeable
    @ReflectiveAccess
    record Step(@NonNull @NotBlank String duration, int eloCost) {
    }

    /**
     * Unknown properties are rejected, so a client that sends an {@code identifier} on create gets a
     * 400 instead of having it silently ignored.
     */
    @Schema(name = "PunishTemplateCreateRequest")
    @Serdeable
    @ReflectiveAccess
    @JsonIgnoreProperties(ignoreUnknown = false)
    record CreateRequest(
            @NonNull @NotBlank String key,
            @NonNull @NotBlank String reason,
            @NonNull @NotBlank String section,
            @Nullable String escalationGroup,
            @Nullable String escalationWindow,
            @NonNull @NotEmpty List<Step> steps,
            @Nullable Map<String, Object> metaData
    ) implements PunishTemplateDTO {
    }

    @Schema(name = "PunishTemplateUpdateRequest")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(
            @NonNull UUID identifier,
            @NonNull @NotBlank String key,
            @NonNull @NotBlank String reason,
            @NonNull @NotBlank String section,
            @Nullable String escalationGroup,
            @Nullable String escalationWindow,
            @NonNull @NotEmpty List<Step> steps,
            @Nullable Map<String, Object> metaData
    ) implements PunishTemplateDTO {
    }

    @Schema(name = "PunishTemplateResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull UUID identifier,
            @NonNull String key,
            @NonNull String reason,
            @NonNull String section,
            @NonNull String escalationGroup,
            @Nullable String escalationWindow,
            @NonNull List<Step> steps,
            @NonNull Map<String, Object> metaData
    ) implements PunishTemplateDTO {
    }

    @Schema(name = "PunishTemplateError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements PunishTemplateDTO, ErrorResponse {
    }
}
