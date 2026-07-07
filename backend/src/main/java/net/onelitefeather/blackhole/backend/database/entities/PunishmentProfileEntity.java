package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.PunishProfileDTO;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Serdeable
@Entity
@IdClass(PunishmentProfileId.class)
@Table(name = "punishment_profiles", indexes = @Index(columnList = "owner"))
public class PunishmentProfileEntity {

    @Id
    private UUID tenantId;

    @Id
    private String owner;

    @OneToOne
    private PunishmentEntity activeChatBan;

    @OneToOne
    private PunishmentEntity activeBan;

    @OneToMany(fetch = FetchType.EAGER)
    private List<PunishmentEntity> history;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData;

    /**
     * Convert a PunishProfile to a PunishmentProfileEntity.
     *
     * @param profile the PunishProfile to convert
     * @return the converted PunishmentProfileEntity
     */
    public static PunishmentProfileEntity toEntity(PunishProfileDTO profile) {
        return new PunishmentProfileEntity(
                profile.tenantId(),
                profile.owner(),
                PunishmentEntity.toEntity(profile.activeChatBan()),
                PunishmentEntity.toEntity(profile.activeBan()),
                PunishmentEntity.toEntities(profile.history()),
                profile.metaData()
        );
    }

    /**
     * Create a new PunishmentProfileEntity.
     */
    public PunishmentProfileEntity() {
        // Default constructor for JPA
    }

    /**
     * Create a new PunishmentProfileEntity.
     *
     * @param tenantId      the tenant the profile belongs to
     * @param owner         the owner of the profile
     * @param activeChatBan the active chat ban
     * @param activeBan     the active ban
     * @param history       the history
     * @param metaData      the metadata
     */
    public PunishmentProfileEntity(
            UUID tenantId,
            String owner,
            PunishmentEntity activeChatBan,
            PunishmentEntity activeBan,
            List<PunishmentEntity> history,
            Map<String, Object> metaData
    ) {
        this.tenantId = tenantId;
        this.owner = owner;
        this.activeChatBan = activeChatBan;
        this.activeBan = activeBan;
        this.history = history;
        this.metaData = metaData;
    }

    /**
     * Set the active ban of the profile.
     *
     * @param activeBan the active ban
     */
    public void setActiveBan(PunishmentEntity activeBan) {
        this.activeBan = activeBan;
    }

    /**
     * Set the active chat ban of the profile.
     *
     * @param activeChatBan the active chat ban
     */
    public void setActiveChatBan(PunishmentEntity activeChatBan) {
        this.activeChatBan = activeChatBan;
    }

    /**
     * Get the tenant the profile belongs to.
     *
     * @return the tenant identifier
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * Get the owner of the profile.
     *
     * @return the owner
     */
    public String getOwner() {
        return owner;
    }

    /**
     * Get the active ban of the profile.
     *
     * @return the active ban
     */
    public PunishmentEntity getActiveBan() {
        return activeBan;
    }

    /**
     * Get the active chat ban of the profile.
     *
     * @return the active chat ban
     */
    public PunishmentEntity getActiveChatBan() {
        return activeChatBan;
    }

    /**
     * Get the history of the profile.
     *
     * @return the history
     */
    public List<PunishmentEntity> getHistory() {
        return history;
    }

    /**
     * Get the metadata of the profile.
     *
     * @return the metadata
     */
    public Map<String, Object> getMetaData() {
        return metaData;
    }

    /**
     * Convert the entity to a DTO.
     *
     * @return the created to variant
     */
    public PunishProfileDTO toDTO() {
        return new PunishProfileDTO(
                this.tenantId,
                this.owner,
                Optional.ofNullable(this.activeChatBan).map(PunishmentEntity::toDTO),
                Optional.ofNullable(this.activeBan).map(PunishmentEntity::toDTO),
                this.history.stream().map(PunishmentEntity::toDTO).toList(),
                this.metaData
        );
    }
}
