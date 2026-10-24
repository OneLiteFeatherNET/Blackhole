package net.onelitefeather.blackhole.backend.report.feedback;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.report.ReportEntity;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.blackhole.backend.report.feedback.dto.ReportFeedbackDTO;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The messages that tell a reporter what became of their report. A message is queued when the report is
 * resolved, handed to the proxy on request and deleted when the proxy acknowledges it, or when it expires.
 * The reporter is identified only by their owner hash, and a message never says which punishment (if any)
 * was applied.
 */
@Singleton
public class ReportFeedbackService {

    private final ReportFeedbackRepository repository;
    private final ReportFeedbackNotifier notifier;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration ttl;

    public ReportFeedbackService(
            ReportFeedbackRepository repository,
            ReportFeedbackNotifier notifier,
            DomainEventPublisher eventPublisher,
            Clock clock,
            @Value("${blackhole.report.feedback.ttl:P14D}") Duration ttl
    ) {
        this.repository = repository;
        this.notifier = notifier;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Queues the message for the reporter of a report that was just resolved, and pokes the proxies. Does
     * nothing unless the outcome is {@code ACTIONED} or {@code DISMISSED}.
     */
    public void queue(UUID reportIdentifier, ReportEntity report, ReportStatus outcome) {
        if (outcome != ReportStatus.ACTIONED && outcome != ReportStatus.DISMISSED) {
            return;
        }
        long now = this.clock.millis();
        ReportFeedbackEntity saved = this.repository.save(new ReportFeedbackEntity(
                report.getReporterHash(), reportIdentifier, report.getCategory(), outcome, now, now + this.ttl.toMillis()));
        this.eventPublisher.publish("report.feedback", Map.of(
                "feedbackIdentifier", saved.getIdentifier().toString(),
                "reportIdentifier", reportIdentifier.toString(),
                "reporterHash", report.getReporterHash(),
                "outcome", outcome.toString()
        ));
        this.notifier.queued(report.getReporterHash());
    }

    /** @return the messages waiting for the reporter that have not expired, oldest first */
    public ReportFeedbackDTO.ListResponse pending(String reporterHash) {
        List<ReportFeedbackDTO.Response> items = this.repository
                .findByReporterHashAndExpiresAtGreaterThanOrderByCreatedAt(reporterHash, this.clock.millis())
                .stream().map(ReportFeedbackEntity::toDTO).toList();
        return new ReportFeedbackDTO.ListResponse(items);
    }

    /** Deletes a message after the proxy showed it. Unknown or already deleted messages are fine: acknowledging repeats safely. */
    public void acknowledge(UUID identifier) {
        this.repository.deleteById(identifier);
    }

    /** @return how many expired messages were deleted */
    public long purgeExpired() {
        return this.repository.deleteByExpiresAtLessThanEquals(this.clock.millis());
    }
}
