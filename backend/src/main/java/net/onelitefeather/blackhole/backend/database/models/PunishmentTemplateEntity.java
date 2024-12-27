package net.onelitefeather.blackhole.backend.database.models;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.blackhole.api.template.PunishTemplate;

import java.util.Map;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishment_templates", indexes = @Index(columnList = "identifier"))
public class PunishmentTemplateEntity {

    @Id
    private UUID identifier;

    private String reason;

    private PunishType type;

    @ElementCollection
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @CollectionTable(name = "punishment_templates_metadata", joinColumns = @JoinColumn(name = "identifier"))
    private Map<String, Object> metaData;

    /**
     * Convert a PunishTemplate to a PunishmentTemplateEntity
     *
     * @param template the PunishTemplate to convert
     * @return the converted PunishmentTemplateEntity
     */
    public static PunishmentTemplateEntity toEntity(PunishTemplate template) {
        return new PunishmentTemplateEntity(template.identifier(), template.reason(), template.type(), template.metaData());
    }

    /**
     * Create a new PunishmentTemplateEntity
     */
    public PunishmentTemplateEntity() {
        // Empty constructor for JPA
    }

    /**
     * Create a new PunishmentTemplateEntity
     *
     * @param identifier the identifier of the template
     * @param reason     the reason of the template
     * @param type       the type of the template
     * @param metaData   the metadata of the template
     */
    public PunishmentTemplateEntity(UUID identifier, String reason, PunishType type, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.reason = reason;
        this.type = type;
        this.metaData = metaData;
    }

    /**
     * Get the identifier of the punishment template.
     *
     * @return the identifier of the template
     */
    public UUID getIdentifier() {
        return identifier;
    }

    /**
     * Get the reason of the punishment template.
     *
     * @return the reason of the template
     */
    public String getReason() {
        return reason;
    }

    /**
     * Get the type of the punishment template.
     *
     * @return the type of the template
     */
    public PunishType getType() {
        return type;
    }

    /**
     * Get the metadata of the punishment template .
     *
     * @return the metadata of the template
     */
    public Map<String, Object> getMetaData() {
        return metaData;
    }

    /**
     * Convert a PunishmentTemplateEntity to a PunishTemplate
     *
     * @return the converted PunishTemplate
     */
    public PunishTemplate toDTO() {
        return PunishTemplate
                .builder(this.metaData)
                .identifier(this.identifier)
                .reason(this.reason)
                .type(this.type)
                .build();
    }
}
