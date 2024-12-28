package net.onelitefeather.blackhole.backend.database.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.api.punish.PunishType;

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

    private PunishType type;

    private UUID source;

    @OneToOne
    private PunishmentTemplateEntity template;

    @ElementCollection
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @CollectionTable(name = "punishments_metadata", joinColumns = @JoinColumn(name = "identifier"))
    private Map<String, Object> metaData;

    /**
     * Convert a PunishEntry to a PunishmentEntity.
     *
     * @param entry the PunishEntry to convert
     * @return the converted PunishmentEntity
     */
    public static PunishmentEntity toEntity(PunishEntry entry) {
        return new PunishmentEntity(
                entry.identifier(),
                entry.type(),
                entry.source(),
                PunishmentTemplateEntity.toEntity(entry.template()),
                entry.metaData()
        );
    }

    public static PunishmentEntity toEntity(Optional<PunishEntry> entityOptional) {
        return entityOptional.map(PunishmentEntity::toEntity).orElse(null);
    }

    public static List<PunishmentEntity> toEntities(List<PunishEntry> entries) {
        return entries.stream().map(PunishmentEntity::toEntity).toList();
    }

    public PunishmentEntity() {
    }

    /**
     * Create a new PunishmentEntity with a template.
     *
     * @param identifier the identifier of the punishment
     * @param type       the type of the punishment
     * @param source     the source of the punishment
     * @param metaData   the metadata of the punishment
     */
    public PunishmentEntity(String identifier, PunishType type, UUID source, PunishmentTemplateEntity template, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.type = type;
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
     * Get the type of the punishment.
     *
     * @return the type
     */
    public PunishType getType() {
        return type;
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
    public PunishEntry toDTO() {
        return PunishEntry.builder()
                .source(source)
                .type(type)
                .template(template.toDTO())
                .build();
    }
}
