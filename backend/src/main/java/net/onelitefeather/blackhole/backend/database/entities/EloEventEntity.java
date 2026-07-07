package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.EloEventDTO;
import net.onelitefeather.blackhole.backend.dto.EloReasonCode;
import net.onelitefeather.blackhole.backend.dto.EloTrack;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail entry for a single ELO change. This is the concrete mechanism that
 * lets a reviewer (or a future Phase 6 appeal) see whether a punishment was an algorithmic
 * decision or a human one - the whole point of the moderator-protection goal behind
 * auto-banning in the first place.
 */
@Serdeable
@Entity
@Table(name = "elo_events", indexes = {@Index(columnList = "identifier"), @Index(columnList = "tenantId"), @Index(columnList = "owner")})
public class EloEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    private UUID tenantId;

    private String owner;

    private EloTrack track;

    private int delta;

    private EloReasonCode reasonCode;

    private UUID sourceEvidenceId;

    private int resultingScore;

    private long createdAt;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    public EloEventEntity() {
        // Empty constructor for JPA
    }

    public EloEventEntity(
            UUID tenantId,
            String owner,
            EloTrack track,
            int delta,
            EloReasonCode reasonCode,
            UUID sourceEvidenceId,
            int resultingScore,
            long createdAt,
            Map<String, Object> metaData
    ) {
        this.tenantId = tenantId;
        this.owner = owner;
        this.track = track;
        this.delta = delta;
        this.reasonCode = reasonCode;
        this.sourceEvidenceId = sourceEvidenceId;
        this.resultingScore = resultingScore;
        this.createdAt = createdAt;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getOwner() {
        return owner;
    }

    public EloTrack getTrack() {
        return track;
    }

    public int getDelta() {
        return delta;
    }

    public EloReasonCode getReasonCode() {
        return reasonCode;
    }

    public UUID getSourceEvidenceId() {
        return sourceEvidenceId;
    }

    public int getResultingScore() {
        return resultingScore;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public EloEventDTO toDTO() {
        return new EloEventDTO(
                this.identifier, this.tenantId, this.owner, this.track, this.delta,
                this.reasonCode, this.sourceEvidenceId, this.resultingScore, this.createdAt, this.metaData
        );
    }
}
