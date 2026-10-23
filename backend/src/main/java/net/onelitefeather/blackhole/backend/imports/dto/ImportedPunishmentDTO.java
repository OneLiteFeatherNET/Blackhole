package net.onelitefeather.blackhole.backend.imports.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code /admin/import/punishments}: imported punishments that have no template yet,
 * and the bulk action that links them to one.
 */
@Schema(description = "Imported punishments without a template")
@Serdeable
public sealed interface ImportedPunishmentDTO {

    /** The most identifiers one request may select. */
    int MAX_SELECTION = 500;

    /**
     * Imported punishments without a template, grouped by their original reason.
     *
     * @param total  how many imported punishments have no template
     * @param groups the groups, the largest first
     */
    @Schema(name = "ImportedPunishmentGroups")
    @Serdeable
    @ReflectiveAccess
    record Groups(int total, @NonNull List<Group> groups) implements ImportedPunishmentDTO {
    }

    /**
     * Imported punishments that share an original reason.
     *
     * @param reason the original vanilla reason ({@code Imported vanilla ban} when it was blank)
     * @param count  how many punishments
     */
    @Schema(name = "ImportedPunishmentGroup")
    @Serdeable
    @ReflectiveAccess
    record Group(@NonNull String reason, int count) {
    }

    /**
     * Links imported punishments to a template. Exactly one of {@code reason} and {@code identifiers} is required.
     *
     * @param templateKey the template to link them to; it must not be in a mute section
     * @param reason      link every imported punishment without a template whose original reason is this text
     * @param identifiers link exactly these punishments (at most {@value #MAX_SELECTION})
     */
    @Schema(name = "ImportedPunishmentRetemplateRequest")
    @Serdeable
    @ReflectiveAccess
    record RetemplateRequest(
            @NonNull @NotBlank String templateKey,
            @Nullable @Size(max = 1000) String reason,
            @Nullable @Size(max = MAX_SELECTION) List<String> identifiers
    ) implements ImportedPunishmentDTO {
    }

    /**
     * What a bulk link did. Running the same request again links nothing: those already linked are {@code unchanged}.
     *
     * @param templateKey  the template they were linked to
     * @param matched      the punishments the request selected
     * @param retemplated  linked to the template now
     * @param unchanged    already linked to this template
     * @param skipped      left alone: not an imported punishment, linked to another template, or in another section than the template
     */
    @Schema(name = "ImportedPunishmentRetemplateResponse")
    @Serdeable
    @ReflectiveAccess
    record RetemplateResponse(
            @NonNull String templateKey,
            int matched,
            int retemplated,
            int unchanged,
            int skipped
    ) implements ImportedPunishmentDTO {
    }

    /** The request was refused and nothing changed. */
    @Schema(name = "ImportedPunishmentError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ImportedPunishmentDTO, ErrorResponse {
    }
}
