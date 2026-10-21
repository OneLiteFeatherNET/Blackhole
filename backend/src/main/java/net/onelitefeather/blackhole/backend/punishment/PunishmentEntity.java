package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
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
@Table(name = "punishments", indexes = {@Index(columnList = "identifier")})
public class PunishmentEntity {

    @Id
    private String identifier;

    private UUID source;

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
        entity.source = entry.source();
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

    public String getIdentifier() {
        return identifier;
    }

    public UUID getSource() {
        return source;
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

    public PunishmentTemplateEntity getTemplate() {
        return template;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public PunishEntryDTO toDTO() {
        return new PunishEntryDTO(
                this.identifier,
                this.source,
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
                this.metaData
        );
    }
}
