package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.AppealDTO;
import net.onelitefeather.blackhole.backend.dto.AppealStatus;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player's appeal against a punishment. {@code createdAt}/{@code updatedAt} are real bigint
 * columns (not JSON metaData), same reasoning as {@code ReportEntity} - the minimum-time-since-
 * punishment eligibility check needs to filter/compare against them directly.
 */
@Serdeable
@Entity
@Table(name = "appeals", indexes = {
        @Index(columnList = "identifier"),
        @Index(columnList = "appellantHash")
})
public class AppealEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    @ManyToOne
    private PunishmentEntity punishment;

    private String appellantHash;

    @Column(length = 2000)
    private String statement;

    private AppealStatus status;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> eligibilityCheckResult = new HashMap<>();

    private UUID decidedBy;

    @Column(length = 2000)
    private String decisionNote;

    private long createdAt;

    private long updatedAt;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    public AppealEntity() {
        // Empty constructor for JPA
    }

    public AppealEntity(
            PunishmentEntity punishment,
            String appellantHash,
            String statement,
            AppealStatus status,
            Map<String, Object> eligibilityCheckResult,
            long createdAt,
            long updatedAt,
            Map<String, Object> metaData
    ) {
        this.punishment = punishment;
        this.appellantHash = appellantHash;
        this.statement = statement;
        this.status = status;
        this.eligibilityCheckResult = eligibilityCheckResult;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public PunishmentEntity getPunishment() {
        return punishment;
    }

    public String getAppellantHash() {
        return appellantHash;
    }

    public String getStatement() {
        return statement;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public void setStatus(AppealStatus status) {
        this.status = status;
    }

    public Map<String, Object> getEligibilityCheckResult() {
        return eligibilityCheckResult;
    }

    public void setEligibilityCheckResult(Map<String, Object> eligibilityCheckResult) {
        this.eligibilityCheckResult = eligibilityCheckResult;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(UUID decidedBy) {
        this.decidedBy = decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
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

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public AppealDTO toDTO() {
        return new AppealDTO(
                this.identifier, this.punishment.getIdentifier(), this.appellantHash, this.statement,
                this.status, this.eligibilityCheckResult, this.decidedBy, this.decisionNote,
                this.createdAt, this.updatedAt, this.metaData
        );
    }
}
