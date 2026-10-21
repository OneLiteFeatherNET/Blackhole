package net.onelitefeather.blackhole.backend.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Wire contract of {@code GET /dashboard/summary}: a read-only aggregate, so it only has a
 * {@link Response} variant - there is no expected failure to report.
 */
@Schema(description = "Aggregated numbers for the admin dashboard")
@Serdeable
public sealed interface DashboardDTO {

    @Schema(name = "DashboardSectionCount", description = "Active punishments held in one section")
    @Serdeable
    @ReflectiveAccess
    record SectionCount(@NonNull String section, long active) {
    }

    @Schema(name = "DashboardEscalation", description = "An automatic permanent punishment")
    @Serdeable
    @ReflectiveAccess
    record Escalation(
            @NonNull String identifier,
            @NonNull @Schema(description = "Hashed owner; open the player detail with it") String owner,
            @NonNull String section,
            @Schema(description = "Creation time in epoch millis") long createdAt,
            @Schema(description = "Whether it has been revoked or lifted since") boolean lifted
    ) {
    }

    @Schema(name = "DashboardSummaryResponse")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull @Schema(description = "Active punishments per section sorted by key; enabled sections are zero-filled")
            List<SectionCount> activePunishmentsBySection,
            @Schema(description = "Reports that are OPEN or UNDER_REVIEW") long openReports,
            @Schema(description = "Appeals that are submitted, eligible or in review") long pendingAppeals,
            @Schema(description = "Automatic permanent punishments created in the last 7 days") long escalationsLast7Days,
            @NonNull @Schema(description = "Most recent automatic permanent punishments, newest first")
            List<Escalation> latestEscalations
    ) implements DashboardDTO {
    }
}
