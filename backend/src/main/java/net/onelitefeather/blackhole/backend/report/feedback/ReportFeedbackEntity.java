package net.onelitefeather.blackhole.backend.report.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.report.ReportCategory;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import net.onelitefeather.blackhole.backend.report.feedback.dto.ReportFeedbackDTO;

import java.util.UUID;

/**
 * A message waiting for the reporter of a resolved report. Holds the reporter's owner hash, the report
 * and its category, and the outcome ({@link ReportStatus#ACTIONED} or {@link ReportStatus#DISMISSED});
 * never the punishment, its details or the resolver. Deliberately not {@code @Serdeable}: entities
 * never leave the backend.
 */
@Entity
@Table(name = "report_feedback", indexes = {
        @Index(columnList = "reporter_hash"),
        @Index(columnList = "expires_at")
})
public class ReportFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    @Column(name = "reporter_hash")
    private String reporterHash;

    @Column(name = "report_identifier")
    private UUID reportIdentifier;

    private ReportCategory category;

    private ReportStatus outcome;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "expires_at")
    private long expiresAt;

    public ReportFeedbackEntity() {
        // Empty constructor for JPA
    }

    public ReportFeedbackEntity(
            String reporterHash, UUID reportIdentifier, ReportCategory category, ReportStatus outcome, long createdAt, long expiresAt
    ) {
        this.reporterHash = reporterHash;
        this.reportIdentifier = reportIdentifier;
        this.category = category;
        this.outcome = outcome;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** A copy of the message that carries its identifier, for stores that do not generate one. */
    public ReportFeedbackEntity withIdentifier(UUID identifier) {
        ReportFeedbackEntity copy = new ReportFeedbackEntity(this.reporterHash, this.reportIdentifier, this.category, this.outcome, this.createdAt, this.expiresAt);
        copy.identifier = identifier;
        return copy;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public String getReporterHash() {
        return reporterHash;
    }

    public UUID getReportIdentifier() {
        return reportIdentifier;
    }

    public ReportCategory getCategory() {
        return category;
    }

    public ReportStatus getOutcome() {
        return outcome;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public ReportFeedbackDTO.Response toDTO() {
        return new ReportFeedbackDTO.Response(this.identifier, this.reportIdentifier, this.category, this.outcome, this.createdAt, this.expiresAt);
    }
}
