package net.onelitefeather.blackhole.velocity.report;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * The decisions behind {@code /report}: who may be reported, the chat evidence attached, and how the
 * backend's answer (including its report rate limit) ends up. The plugin adds no limit of its own; the
 * backend's limiter answers 429, which is passed on as {@link Outcome.RateLimited}.
 */
public final class ReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportService.class);

    /** The longest description the backend accepts. */
    public static final int MAX_DETAILS = 1000;

    /** How a report ended. */
    public sealed interface Outcome {

        /**
         * The report is recorded.
         *
         * @param report   the recorded report
         * @param evidence what became of the chat evidence
         */
        record Submitted(@NotNull ReportDTO report, @NotNull ReportEvidence.Result evidence) implements Outcome {
        }

        /** The reporter named themselves. */
        record SelfReport() implements Outcome {
        }

        /** The reported player holds the bypass permission. */
        record Protected() implements Outcome {
        }

        /** The reason {@code other} needs a description. */
        record DetailsRequired() implements Outcome {
        }

        /** The description is longer than the backend accepts. */
        record DetailsTooLong(int max) implements Outcome {
        }

        /** The backend's report limiter refused the report. */
        record RateLimited() implements Outcome {
        }

        /** The backend could not take the report. */
        record Failed() implements Outcome {
        }
    }

    private final BackendGateway backend;
    private final ReportEvidence evidence;

    public ReportService(@NotNull BackendGateway backend, @NotNull ReportEvidence evidence) {
        this.backend = backend;
        this.evidence = evidence;
    }

    /**
     * @param reporter the reporting player
     * @param reported the reported player
     * @param bypass   whether the reported player is protected from reports by permission
     * @param category the reason
     * @param details  the description the reporter typed, if any
     */
    public @NotNull Outcome submit(
            @NotNull UUID reporter, @NotNull UUID reported, boolean bypass, @NotNull ReportCategory category, @Nullable String details
    ) {
        if (reporter.equals(reported)) {
            return new Outcome.SelfReport();
        }
        if (bypass) {
            return new Outcome.Protected();
        }
        String description = details == null || details.isBlank() ? null : details.strip();
        if (description == null && category == ReportCategory.OTHER) {
            return new Outcome.DetailsRequired();
        }
        if (description != null && description.length() > MAX_DETAILS) {
            return new Outcome.DetailsTooLong(MAX_DETAILS);
        }
        String reportedHash = UUIDConverter.convertToSHA(reported);
        ReportEvidence.Result attached = this.evidence.attach(reportedHash);
        List<UUID> references = attached.evidenceId() == null ? List.of() : List.of(attached.evidenceId());
        try {
            ReportDTO report = this.backend.submitReport(UUIDConverter.convertToSHA(reporter), reportedHash, category, description, references);
            return new Outcome.Submitted(report, attached);
        } catch (ApiException e) {
            if (e.getCode() == 429) {
                return new Outcome.RateLimited();
            }
            LOGGER.error("The backend refused a report (HTTP {})", e.getCode());
            return new Outcome.Failed();
        } catch (RuntimeException e) {
            LOGGER.error("A report could not be sent ({})", e.getClass().getSimpleName());
            return new Outcome.Failed();
        }
    }
}
