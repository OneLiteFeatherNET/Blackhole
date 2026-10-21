package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileDTO;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileRequestDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A player's punishment state: at most one active punishment per section, plus the history of every
 * punishment that was replaced, expired or lifted.
 */
@Serdeable
@Entity
@Table(name = "punishment_profiles")
public class PunishmentProfileEntity {

    @Id
    private String owner;

    /**
     * The active punishment per section key. Applying a punishment replaces only the slot of its
     * own section.
     */
    @OneToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "active_punishments",
            joinColumns = @JoinColumn(name = "owner"),
            inverseJoinColumns = @JoinColumn(name = "punishment_identifier")
    )
    @MapKeyColumn(name = "section_key")
    private Map<String, PunishmentEntity> active = new HashMap<>();

    @OneToMany(fetch = FetchType.EAGER)
    private List<PunishmentEntity> history;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData;

    /**
     * Convert a PunishProfileRequestDTO to a PunishmentProfileEntity.
     *
     * @param profile the PunishProfileRequestDTO to convert
     * @return the converted PunishmentProfileEntity
     */
    public static PunishmentProfileEntity toEntity(PunishProfileRequestDTO profile) {
        Map<String, PunishmentEntity> active = new HashMap<>();
        if (profile.active() != null) {
            profile.active().forEach((section, entry) -> active.put(section, PunishmentEntity.toEntity(entry, profile.owner())));
        }
        return new PunishmentProfileEntity(
                profile.owner(),
                active,
                PunishmentEntity.toEntities(profile.history(), profile.owner()),
                profile.metaData()
        );
    }

    public PunishmentProfileEntity() {
        // Default constructor for JPA
    }

    /**
     * Create a new PunishmentProfileEntity.
     *
     * @param owner    the owner of the profile
     * @param active   the active punishment per section key
     * @param history  the history
     * @param metaData the metadata
     */
    public PunishmentProfileEntity(
            String owner,
            Map<String, PunishmentEntity> active,
            List<PunishmentEntity> history,
            Map<String, Object> metaData
    ) {
        this.owner = owner;
        this.active = active;
        this.history = history;
        this.metaData = metaData;
    }

    public String getOwner() {
        return owner;
    }

    /**
     * The active punishment per section key; mutate it to occupy or clear a slot.
     */
    public Map<String, PunishmentEntity> getActive() {
        return active;
    }

    public List<PunishmentEntity> getHistory() {
        return history;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public PunishProfileDTO toDTO() {
        Map<String, net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO> activeDtos = this.active.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toDTO(), (a, b) -> a, LinkedHashMap::new));
        return new PunishProfileDTO(
                this.owner,
                activeDtos,
                this.history.stream().map(PunishmentEntity::toDTO).toList(),
                this.metaData
        );
    }
}
