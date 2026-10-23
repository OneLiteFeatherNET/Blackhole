package net.onelitefeather.blackhole.backend.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * One piece of evidence. It belongs to a punishment ({@link #getPunishmentIdentifier()}), or - while
 * it is pending - only to an owner and a client-chosen correlation id until a punishment claims it.
 * Exactly one of {@link #getPayload()}, {@link #getExternalRef()} and {@link #getFileKey()} is the
 * primary content; the service guarantees that, not the schema.
 *
 * <p>Deliberately not {@code @Serdeable}: entities never leave the backend, the API only returns
 * {@code EvidenceDTO.Response}.
 */
@Entity
@Table(name = "evidence_items")
public class EvidenceItemEntity {

    @Id
    private UUID identifier;

    @Column(name = "punishment_identifier")
    private String punishmentIdentifier;

    private String owner;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    private EvidenceKind kind;

    @Column(name = "source_system", length = 64)
    private String sourceSystem;

    @Column(name = "external_ref", length = 2048)
    private String externalRef;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    @Column(name = "payload_schema_version")
    private int payloadSchemaVersion = 1;

    @Column(name = "captured_at")
    private Long capturedAt;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    /** The {@code oid} of the verified principal that attached the item; {@code null} for pre-SSO rows. */
    @Column(name = "recorded_by_oid")
    private UUID recordedByOid;

    /** The staff member the in-game {@code createdBy} was linked to when the item was attached; {@code null} if unlinked. */
    @Column(name = "actor_oid")
    private UUID actorOid;

    @Column(name = "file_key")
    private String fileKey;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_content_type", length = 100)
    private String fileContentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    private EvidenceStatus status = EvidenceStatus.VALID;

    @Column(name = "status_changed_at")
    private Long statusChangedAt;

    @Column(name = "lifted_by_appeal")
    private UUID liftedByAppeal;

    public EvidenceItemEntity() {
        // Empty constructor for JPA
    }

    /**
     * Creates a valid item.
     *
     * @param identifier           the new item's id
     * @param punishmentIdentifier the punishment it belongs to, or {@code null} while pending
     * @param owner                the hashed owner
     * @param correlationId        the pending claim key, or {@code null}
     * @param kind                 what the item is
     * @param sourceSystem         the unverified system that sent it
     * @param createdAt            creation time in epoch millis
     */
    public EvidenceItemEntity(
            UUID identifier, String punishmentIdentifier, String owner, String correlationId,
            EvidenceKind kind, String sourceSystem, long createdAt
    ) {
        this.identifier = identifier;
        this.punishmentIdentifier = punishmentIdentifier;
        this.owner = owner;
        this.correlationId = correlationId;
        this.kind = kind;
        this.sourceSystem = sourceSystem;
        this.createdAt = createdAt;
    }

    /** Stores the primary content of a structured or link item. */
    public void setContent(String externalRef, Map<String, Object> payload) {
        this.externalRef = externalRef;
        this.payload = payload;
    }

    /** Stores the primary content of an uploaded file. */
    public void setFile(String fileKey, String fileName, String contentType, long size, String sha256) {
        this.fileKey = fileKey;
        this.fileName = fileName;
        this.fileContentType = contentType;
        this.fileSize = size;
        this.fileSha256 = sha256;
    }

    /** Attaches a pending item to the punishment that claimed it; the correlation id is spent. */
    public void claim(String punishmentIdentifier) {
        this.punishmentIdentifier = punishmentIdentifier;
        this.correlationId = null;
    }

    /** Marks the item as no longer supporting a current punishment. */
    public void markStatus(EvidenceStatus status, long at, UUID liftedByAppeal) {
        this.status = status;
        this.statusChangedAt = at;
        this.liftedByAppeal = liftedByAppeal;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getActorOid() {
        return actorOid;
    }

    public void setActorOid(UUID actorOid) {
        this.actorOid = actorOid;
    }

    public void setRecordedByOid(UUID recordedByOid) {
        this.recordedByOid = recordedByOid;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public String getPunishmentIdentifier() {
        return punishmentIdentifier;
    }

    public String getOwner() {
        return owner;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public EvidenceKind getKind() {
        return kind;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public int getPayloadSchemaVersion() {
        return payloadSchemaVersion;
    }

    public Long getCapturedAt() {
        return capturedAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getRecordedByOid() {
        return recordedByOid;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileContentType() {
        return fileContentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public Long getStatusChangedAt() {
        return statusChangedAt;
    }

    public UUID getLiftedByAppeal() {
        return liftedByAppeal;
    }

    /** Whether the item has no punishment yet and waits to be claimed. */
    public boolean isPending() {
        return this.punishmentIdentifier == null;
    }
}
