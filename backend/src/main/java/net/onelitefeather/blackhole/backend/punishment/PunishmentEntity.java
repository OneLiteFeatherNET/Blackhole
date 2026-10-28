package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.phoca.metadata.Metadata;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One applied punishment. A staff punishment comes from a template and is temporary; the system's
 * escalation punishments have no template and are marked {@link #isPermanent()}. The legacy
 * {@code type} column is no longer mapped.
 */
@Serdeable
@Entity
@Table(name = "punishments", indexes = {@Index(columnList = "identifier"), @Index(name = "idx_punishments_short_id", columnList = "short_id")})
public class PunishmentEntity {

    @Id
    private String identifier;

    /**
     * The key of the short ID players quote ({@link PunishmentShortId}), derived from the identifier.
     * Not unique: four bytes collide.
     */
    @Column(name = "short_id", length = 8, nullable = false)
    private String shortId;

    private UUID source;

    /**
     * The {@code oid} of the verified principal that wrote the punishment; {@code null} for rows
     * written before SSO, which are shown as unverified.
     */
    @Column(name = "recorded_by_oid")
    private UUID recordedByOid;

    /**
     * The staff member {@code source} was linked to when an in-game punishment was written through the
     * proxy; {@code null} for web writes, unlinked UUIDs and every row from before the link existed.
     */
    @Column(name = "actor_oid")
    private UUID actorOid;

    /**
     * The hashed owner. Needed on the punishment itself so escalation step selection can count a
     * player's earlier punishments in a group.
     */
    private String owner;

    @Column(name = "section_key")
    private String sectionKey;

    /**
     * Optional scope identifier (e.g. an event or community) this punishment is restricted to.
     * {@code null} means the punishment applies network-wide.
     */
    private String scope;

    private boolean permanent;

    /**
     * Set when the punishment was revoked or lifted by a granted appeal, so escalation step
     * selection does not count it.
     */
    private boolean lifted;

    private Long createdAt;

    private String escalationGroup;

    private Integer stepIndex;

    private Integer stepCount;

    /** The ELO deducted when this punishment was applied; what a lifting appeal gives back. */
    private Integer eloCost;

    /** Whether this row is a punishment or a kick. Kicks never occupy an active slot but count for escalation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private PunishmentKind kind = PunishmentKind.PUNISHMENT;

    /** When the player acknowledged a warning (epoch millis, set once); {@code null} for anything else or while pending. */
    @Column(name = "acknowledged_at")
    private Long acknowledgedAt;

    /** The scope a kick was applied at; {@code null} for a punishment. */
    @Column(name = "kick_scope", length = 16)
    private String kickScope;

    /** The server or section a scoped kick targeted; {@code null} for a punishment or a network kick. */
    @Column(name = "kick_target")
    private String kickTarget;

    @ManyToOne
    private PunishmentTemplateEntity template;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    /**
     * Rebuilds a punishment from its DTO form, for callers that submit whole profiles.
     */
    public static PunishmentEntity toEntity(PunishEntryDTO entry, String owner) {
        PunishmentEntity entity = new PunishmentEntity();
        entity.identifier = entry.identifier();
        entity.shortId = PunishmentShortId.storageKey(entry.identifier());
        entity.source = entry.source();
        entity.recordedByOid = entry.recordedByOid();
        entity.actorOid = entry.actorOid();
        entity.owner = owner;
        entity.sectionKey = entry.section();
        entity.scope = entry.scope();
        entity.permanent = entry.permanent();
        entity.lifted = entry.lifted();
        entity.createdAt = entry.createdAt();
        entity.escalationGroup = entry.escalationGroup();
        entity.stepIndex = entry.stepIndex();
        entity.stepCount = entry.stepCount();
        entity.eloCost = entry.eloCost();
        entity.template = entry.template() == null ? null : PunishmentTemplateEntity.toEntity(entry.template());
        if (entry.kind() == PunishmentKind.KICK) {
            entity.recordKick(entry.kickScope(), entry.kickTarget());
        } else if (entry.kind() == PunishmentKind.WARN) {
            entity.kind = PunishmentKind.WARN;
            entity.acknowledgedAt = entry.acknowledgedAt();
        }
        entity.metaData = entry.metaData();
        return entity;
    }

    public static List<PunishmentEntity> toEntities(List<PunishEntryDTO> entries, String owner) {
        return entries == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(entries.stream().map(entry -> toEntity(entry, owner)).toList());
    }

    public PunishmentEntity() {
        // Empty constructor for JPA
    }

    /**
     * Creates a punishment.
     *
     * @param identifier the identifier of the punishment
     * @param source     the source of the punishment
     * @param owner      the hashed owner it applies to
     * @param sectionKey the section it belongs to
     * @param permanent  whether it is a system escalation punishment without an expiry
     * @param createdAt  creation time in epoch millis
     * @param template   the template it came from, or {@code null} for system punishments
     * @param metaData   the metadata of the punishment
     */
    public PunishmentEntity(
            String identifier,
            UUID source,
            String owner,
            String sectionKey,
            boolean permanent,
            long createdAt,
            PunishmentTemplateEntity template,
            Map<String, Object> metaData
    ) {
        this.identifier = identifier;
        this.shortId = PunishmentShortId.storageKey(identifier);
        this.source = source;
        this.owner = owner;
        this.sectionKey = sectionKey;
        this.permanent = permanent;
        this.createdAt = createdAt;
        this.template = template;
        this.metaData = metaData;
    }

    /**
     * Records which escalation step this punishment came from and what it cost.
     */
    public void recordEscalation(String group, int stepIndex, int stepCount, int eloCost) {
        this.escalationGroup = group;
        this.stepIndex = stepIndex;
        this.stepCount = stepCount;
        this.eloCost = eloCost;
    }

    /**
     * Links the punishment to a template after the fact (an imported ban that had none). The original
     * reason is kept for staff as {@code importedReason}; nothing about the duration, section or escalation changes.
     */
    public void retemplate(PunishmentTemplateEntity template, String importedReason, long now) {
        this.template = template;
        this.metaData.putIfAbsent("importedReason", importedReason);
        this.metaData.put(Metadata.META_DATA_KEY_UPDATE_DATE, now);
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getShortId() {
        return shortId;
    }

    public UUID getSource() {
        return source;
    }

    public UUID getRecordedByOid() {
        return recordedByOid;
    }

    public void setRecordedByOid(UUID recordedByOid) {
        this.recordedByOid = recordedByOid;
    }

    public UUID getActorOid() {
        return actorOid;
    }

    public void setActorOid(UUID actorOid) {
        this.actorOid = actorOid;
    }

    public String getOwner() {
        return owner;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public String getScope() {
        return scope;
    }

    public boolean isPermanent() {
        return permanent;
    }

    public void setPermanent(boolean permanent) {
        this.permanent = permanent;
    }

    public boolean isLifted() {
        return lifted;
    }

    public void setLifted(boolean lifted) {
        this.lifted = lifted;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public String getEscalationGroup() {
        return escalationGroup;
    }

    public Integer getStepIndex() {
        return stepIndex;
    }

    public Integer getStepCount() {
        return stepCount;
    }

    public Integer getEloCost() {
        return eloCost;
    }

    public PunishmentKind getKind() {
        return kind;
    }

    public String getKickScope() {
        return kickScope;
    }

    public String getKickTarget() {
        return kickTarget;
    }

    /** Marks this row as a warning: stored like a kick, never active, acknowledged once by the player. */
    public void recordWarning() {
        this.kind = PunishmentKind.WARN;
    }

    public boolean isWarning() {
        return this.kind == PunishmentKind.WARN;
    }

    /** When the player acknowledged this warning, or {@code null} while it is pending. */
    public Long getAcknowledgedAt() {
        return acknowledgedAt;
    }

    /**
     * Records the player's acknowledgement once. A repeat keeps the first time.
     *
     * @return whether this call set the time (false when it was already acknowledged)
     */
    public boolean acknowledge(long at) {
        if (this.acknowledgedAt != null) {
            return false;
        }
        this.acknowledgedAt = at;
        return true;
    }

    /** Marks this row as a kick at {@code scope}; {@code target} is {@code null} for a network kick. */
    public void recordKick(String scope, String target) {
        this.kind = PunishmentKind.KICK;
        this.kickScope = scope;
        this.kickTarget = target;
    }

    /** Whether this row can be an active punishment: only a {@link PunishmentKind#PUNISHMENT} can. Kicks and warnings never are. */
    public boolean canBeActive() {
        return this.kind == PunishmentKind.PUNISHMENT;
    }

    public PunishmentTemplateEntity getTemplate() {
        return template;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public PunishEntryDTO toDTO() {
        return new PunishEntryDTO(
                this.identifier,
                PunishmentShortId.display(this.shortId),
                this.source,
                this.recordedByOid,
                this.recordedByOid != null,
                this.actorOid,
                null,
                this.sectionKey,
                this.scope,
                this.permanent,
                this.lifted,
                this.createdAt,
                this.escalationGroup,
                this.stepIndex,
                this.stepCount,
                this.eloCost,
                this.template == null ? null : this.template.toDTO(),
                null,
                this.kind,
                this.kickScope,
                this.kickTarget,
                this.acknowledgedAt,
                this.metaData
        );
    }
}
