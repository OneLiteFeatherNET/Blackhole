package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.EvidenceType;
import net.onelitefeather.blackhole.backend.dto.PunishmentEvidenceDTO;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishment_evidence", indexes = {@Index(columnList = "identifier")})
public class PunishmentEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    @ManyToOne
    private PunishmentEntity punishment;

    private EvidenceType evidenceType;

    private String referenceId;

    private String capturedContentHash;

    private Long retentionExpiresAt;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    /**
     * Create a new PunishmentEvidenceEntity.
     */
    public PunishmentEvidenceEntity() {
        // Empty constructor for JPA
    }

    /**
     * Create a new PunishmentEvidenceEntity with the given values.
     *
     * @param punishment          the punishment this evidence is attached to
     * @param evidenceType        where this evidence originated from
     * @param referenceId         an external reference (e.g. a chat log message id), if any
     * @param capturedContentHash a hash of the captured content, if any
     * @param retentionExpiresAt  epoch millis after which this evidence should be erased, if bounded
     * @param metaData            the metadata of the evidence entry
     */
    public PunishmentEvidenceEntity(
            PunishmentEntity punishment,
            EvidenceType evidenceType,
            String referenceId,
            String capturedContentHash,
            Long retentionExpiresAt,
            Map<String, Object> metaData
    ) {
        this.punishment = punishment;
        this.evidenceType = evidenceType;
        this.referenceId = referenceId;
        this.capturedContentHash = capturedContentHash;
        this.retentionExpiresAt = retentionExpiresAt;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public PunishmentEntity getPunishment() {
        return punishment;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getCapturedContentHash() {
        return capturedContentHash;
    }

    public Long getRetentionExpiresAt() {
        return retentionExpiresAt;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    /**
     * Convert this PunishmentEvidenceEntity to a PunishmentEvidenceDTO.
     *
     * @return the converted DTO
     */
    public PunishmentEvidenceDTO toDTO() {
        return new PunishmentEvidenceDTO(
                this.identifier,
                this.punishment.getIdentifier(),
                this.evidenceType,
                this.referenceId,
                this.capturedContentHash,
                this.retentionExpiresAt,
                this.metaData
        );
    }
}
