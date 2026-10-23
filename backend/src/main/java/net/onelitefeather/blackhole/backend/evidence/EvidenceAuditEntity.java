package net.onelitefeather.blackhole.backend.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One line of the append-only evidence audit trail. It has no foreign keys, so it outlives the
 * evidence it describes, and it never holds evidence content - only ids, the hashed owner, who
 * (the attributed actor and the verified principal that recorded it) did what, and an admin delete's reason and note.
 */
@Entity
@Table(name = "evidence_audit")
public class EvidenceAuditEntity {

    @Id
    private UUID identifier;

    @Column(name = "evidence_identifier")
    private UUID evidenceIdentifier;

    @Column(name = "punishment_identifier")
    private String punishmentIdentifier;

    private String owner;

    private EvidenceAuditAction action;

    private UUID actor;

    /** The {@code oid} of the verified principal behind the action; {@code null} for system actions and pre-SSO rows. */
    @Column(name = "recorded_by_oid")
    private UUID recordedByOid;

    @Column(length = 32)
    private String reason;

    @Column(length = 500)
    private String note;

    @Column(name = "occurred_at")
    private long occurredAt;

    public EvidenceAuditEntity() {
        // Empty constructor for JPA
    }

    public EvidenceAuditEntity(
            UUID identifier, UUID evidenceIdentifier, String punishmentIdentifier, String owner,
            EvidenceAuditAction action, UUID actor, UUID recordedByOid, String reason, String note, long occurredAt
    ) {
        this.identifier = identifier;
        this.evidenceIdentifier = evidenceIdentifier;
        this.punishmentIdentifier = punishmentIdentifier;
        this.owner = owner;
        this.action = action;
        this.actor = actor;
        this.recordedByOid = recordedByOid;
        this.reason = reason;
        this.note = note;
        this.occurredAt = occurredAt;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public UUID getEvidenceIdentifier() {
        return evidenceIdentifier;
    }

    public String getPunishmentIdentifier() {
        return punishmentIdentifier;
    }

    public String getOwner() {
        return owner;
    }

    public EvidenceAuditAction getAction() {
        return action;
    }

    public UUID getActor() {
        return actor;
    }

    public UUID getRecordedByOid() {
        return recordedByOid;
    }

    public String getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public long getOccurredAt() {
        return occurredAt;
    }
}
