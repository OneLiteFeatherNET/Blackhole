package net.onelitefeather.blackhole.backend.punishment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import net.onelitefeather.blackhole.backend.punishment.StepKind;
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
    /**
     * Documents the body of {@code GET /template}: the JSON Micronaut Data's {@code Page} serialises
     * to, with the templates under {@code content}. Never instantiated - the controller returns the
     * {@code Page} itself, so this exists only to give the published spec the real shape instead of
     * a bare array.
     *
     * @param content          the templates of this page
     * @param totalSize        the number of templates across all pages
     * @param totalPages       the number of pages
     * @param pageNumber       the zero-based number of this page
     * @param size             the requested page size
     * @param offset           the index of the first template of this page
     * @param numberOfElements the number of templates on this page
     * @param empty            whether this page has no templates
     */
    @Schema(name = "PunishTemplatePage")
    @Serdeable
    @ReflectiveAccess
    record PageResponse(
            @NonNull List<Response> content,
            long totalSize,
            int totalPages,
            int pageNumber,
            int size,
            long offset,
            int numberOfElements,
            boolean empty
    ) implements PunishTemplateDTO {
    }

    @Schema(name = "PunishTemplateStep")
    @Serdeable
    @ReflectiveAccess
    record Step(@Nullable String duration, int eloCost, @Nullable StepKind kind) {

        /** A timed punishment step, the only kind before kicks existed. */
        public Step(String duration, int eloCost) {
            this(duration, eloCost, StepKind.PUNISH);
        }
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
            @Nullable String warnWindow,
            @NonNull @NotEmpty List<Step> steps,
            @Nullable Map<String, Object> metaData
    ) implements PunishTemplateDTO {

        /** A template without a warn window: warnings count within the escalation window. */
        public CreateRequest(String key, String reason, String section, String escalationGroup, String escalationWindow,
                             List<Step> steps, Map<String, Object> metaData) {
            this(key, reason, section, escalationGroup, escalationWindow, null, steps, metaData);
        }
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
            @Nullable String warnWindow,
            @NonNull @NotEmpty List<Step> steps,
            @Nullable Map<String, Object> metaData
    ) implements PunishTemplateDTO {

        /** An update without a warn window: warnings count within the escalation window. */
        public UpdateRequest(UUID identifier, String key, String reason, String section, String escalationGroup,
                             String escalationWindow, List<Step> steps, Map<String, Object> metaData) {
            this(identifier, key, reason, section, escalationGroup, escalationWindow, null, steps, metaData);
        }
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
            @Nullable String warnWindow,
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
