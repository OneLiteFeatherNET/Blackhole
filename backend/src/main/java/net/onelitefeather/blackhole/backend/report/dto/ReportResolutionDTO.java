package net.onelitefeather.blackhole.backend.report.dto;

import net.onelitefeather.blackhole.backend.report.ReportStatus;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for resolving a report. Providing {@code punishmentTemplateId} applies that template
 * to the reported player as part of the same resolution - the report system's actual integration
 * point with punishments. The resolver, and the source of an applied punishment, is the caller of
 * the verified token; the request carries no actor.
 *
 * @param status               the resolution outcome
 * @param resolutionNote       an optional note explaining the decision
 * @param punishmentTemplateId an optional template to apply to the reported player
 */
@Serdeable
@ReflectiveAccess
public record ReportResolutionDTO(
        @NonNull ReportStatus status,
        @Nullable @Size(max = 1000) String resolutionNote,
        @Nullable UUID punishmentTemplateId
) {
}
