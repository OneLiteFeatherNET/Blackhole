package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.ReportCategory;
import net.onelitefeather.blackhole.backend.dto.ReportDTO;
import net.onelitefeather.blackhole.backend.dto.ReportStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code createdAt}/{@code updatedAt} are real columns rather than metaData entries (unlike
 * most other entities in this codebase) so the report-submission rate limit can filter on them
 * directly - Micronaut Data can't derive a query against a field buried inside a JSON blob.
 */
@Serdeable
@Entity
@Table(name = "reports", indexes = {
        @Index(columnList = "identifier"),
        @Index(columnList = "reporterHash")
})
public class ReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    private String reporterHash;

    private String reportedHash;

    private ReportCategory category;

    @Column(length = 1000)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_evidence_references", joinColumns = @jakarta.persistence.JoinColumn(name = "report_identifier"))
    @Column(name = "evidence_identifier")
    private List<UUID> evidenceReferences = new ArrayList<>();

    private ReportStatus status;

    private long createdAt;

    private long updatedAt;

    private UUID resolvedBy;

    @Column(length = 1000)
    private String resolutionNote;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    public ReportEntity() {
        // Empty constructor for JPA
    }

    public ReportEntity(
            String reporterHash,
            String reportedHash,
            ReportCategory category,
            String description,
            List<UUID> evidenceReferences,
            ReportStatus status,
            long createdAt,
            long updatedAt,
            UUID resolvedBy,
            String resolutionNote,
            Map<String, Object> metaData
    ) {
        this.reporterHash = reporterHash;
        this.reportedHash = reportedHash;
        this.category = category;
        this.description = description;
        this.evidenceReferences = evidenceReferences;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resolvedBy = resolvedBy;
        this.resolutionNote = resolutionNote;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public String getReporterHash() {
        return reporterHash;
    }

    public String getReportedHash() {
        return reportedHash;
    }

    public ReportCategory getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public List<UUID> getEvidenceReferences() {
        return evidenceReferences;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(UUID resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public ReportDTO toDTO() {
        return new ReportDTO(
                this.identifier,
                this.reporterHash,
                this.reportedHash,
                this.category,
                this.description,
                this.evidenceReferences,
                this.status,
                this.createdAt,
                this.updatedAt,
                this.resolvedBy,
                this.resolutionNote,
                this.metaData
        );
    }
}
