package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.PunishEntryDTO;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishments", indexes = @Index(columnList = "identifier"))
public class PunishmentEntity {

    @Id
    private String identifier;

    private UUID source;

    @ManyToOne
    private PunishmentTemplateEntity template;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    /**
     * Convert a PunishEntry to a PunishmentEntity.
     *
     * @param entry the PunishEntry to convert
     * @return the converted PunishmentEntity
     */
    public static PunishmentEntity toEntity(PunishEntryDTO entry) {
        return new PunishmentEntity(
                entry.identifier(),
                entry.source(),
                PunishmentTemplateEntity.toEntity(entry.template()),
                entry.metaData()
        );
    }

    public static PunishmentEntity toEntity(Optional<PunishEntryDTO> entityOptional) {
        return entityOptional.map(PunishmentEntity::toEntity).orElse(null);
    }

    public static List<PunishmentEntity> toEntities(List<PunishEntryDTO> entries) {
        return entries.stream().map(PunishmentEntity::toEntity).toList();
    }

    public PunishmentEntity() {
    }

    /**
     * Create a new PunishmentEntity with a template.
     *
     * @param identifier the identifier of the punishment
     * @param source     the source of the punishment
     * @param metaData   the metadata of the punishment
     */
    public PunishmentEntity(String identifier, UUID source, PunishmentTemplateEntity template, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.source = source;
        this.template = template;
        this.metaData = metaData;
    }

    /**
     * Set's the template of the punishment.
     *
     * @param template the template to set
     */
    public void setTemplate(PunishmentTemplateEntity template) {
        this.template = template;
    }

    /**
     * Get the identifier of the punishment.
     *
     * @return the identifier
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Get the source of the punishment.
     *
     * @return the source
     */
    public UUID getSource() {
        return source;
    }

    /**
     * Get the template of the punishment.
     *
     * @return the template
     */
    public PunishmentTemplateEntity getTemplate() {
        return template;
    }

    /**
     * Get the metadata of the punishment.
     *
     * @return the metadata
     */
    public Map<String, Object> getMetaData() {
        return metaData;
    }

    /**
     * Convert a PunishmentEntity to a PunishEntry
     *
     * @return the converted PunishEntry
     */
    public PunishEntryDTO toDTO() {
        return new PunishEntryDTO(
                this.identifier,
                this.source,
                this.template.toDTO(),
                this.metaData
        );
    }
}
