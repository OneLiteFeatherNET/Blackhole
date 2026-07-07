package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for resolving a report. Providing {@code punishmentTemplateId} (together with
 * {@code punishmentSource}) applies that template to the reported player as part of the same
 * resolution - the report system's actual integration point with punishments.
 *
 * @param status               the resolution outcome
 * @param resolutionNote       an optional note explaining the decision
 * @param resolvedBy           the staff/system identity resolving this report
 * @param punishmentTemplateId an optional template to apply to the reported player
 * @param punishmentSource     the staff/system identity applying the punishment; required if {@code punishmentTemplateId} is set
 */
@Serdeable
@ReflectiveAccess
public record ReportResolutionDTO(
        @NonNull ReportStatus status,
        @Nullable @Size(max = 1000) String resolutionNote,
        @NonNull UUID resolvedBy,
        @Nullable UUID punishmentTemplateId,
        @Nullable UUID punishmentSource
) {
}
