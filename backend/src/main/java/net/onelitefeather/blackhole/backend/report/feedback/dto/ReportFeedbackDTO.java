package net.onelitefeather.blackhole.backend.report.feedback.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.report.ReportCategory;
import net.onelitefeather.blackhole.backend.report.ReportStatus;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of the report feedback routes: what the proxy tells a reporter about the outcome of a
 * report. It names the report's category and whether action was taken, never the punishment.
 */
@Schema(description = "A message about the outcome of a report, for its reporter")
@Serdeable
public sealed interface ReportFeedbackDTO {

    /**
     * One waiting message.
     *
     * @param identifier       the message, which the proxy acknowledges after showing it
     * @param reportIdentifier the report it is about
     * @param category         the report's category
     * @param outcome          {@code ACTIONED} (action was taken) or {@code DISMISSED} (no action)
     * @param createdAt        when the report was resolved, epoch millis
     * @param expiresAt        when the message is dropped if nobody acknowledged it, epoch millis
     */
    @Schema(name = "ReportFeedbackResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull UUID identifier,
            @NonNull UUID reportIdentifier,
            @NonNull ReportCategory category,
            @NonNull ReportStatus outcome,
            long createdAt,
            long expiresAt
    ) implements ReportFeedbackDTO {
    }

    /**
     * The messages waiting for one reporter, oldest first.
     *
     * @param items the messages that have not expired
     */
    @Schema(name = "ReportFeedbackList")
    @Serdeable
    @ReflectiveAccess
    record ListResponse(@NonNull List<Response> items) implements ReportFeedbackDTO {
    }
}
