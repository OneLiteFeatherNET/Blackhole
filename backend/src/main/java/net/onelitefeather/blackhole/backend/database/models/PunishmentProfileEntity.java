package net.onelitefeather.blackhole.backend.database.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.api.profile.PunishProfile;

import java.util.List;
import java.util.Map;

@Serdeable
@Entity
@Table(name = "punishment_profiles", indexes = @Index(columnList = "owner"))
public class PunishmentProfileEntity {

    @Id
    private String owner;

    @OneToOne
    private PunishmentEntity activeChatBan;

    @OneToOne
    private PunishmentEntity activeBan;

    @OneToMany
    private List<PunishmentEntity> history;

    @ElementCollection
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @CollectionTable(name = "punishment_profiles_metadata", joinColumns = @JoinColumn(name = "owner"))
    private Map<String, Object> metaData;

    /**
     * Convert a PunishProfile to a PunishmentProfileEntity.
     *
     * @param profile the PunishProfile to convert
     * @return the converted PunishmentProfileEntity
     */
    public static PunishmentProfileEntity toEntity(PunishProfile profile) {
        return new PunishmentProfileEntity(
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
     * @param owner         the owner of the profile
     * @param activeChatBan the active chat ban
     * @param activeBan     the active ban
     * @param history       the history
     * @param metaData      the metadata
     */
    public PunishmentProfileEntity(
            String owner,
            PunishmentEntity activeChatBan,
            PunishmentEntity activeBan,
            List<PunishmentEntity> history,
            Map<String, Object> metaData
    ) {
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
    public PunishProfile toDTO() {
        return PunishProfile.builder()
                .owner(this.owner)
                .metaData(this.metaData)
                .activeChatBan(this.activeChatBan.toDTO())
                .activeBan(this.activeBan.toDTO())
                .build();
    }
}
