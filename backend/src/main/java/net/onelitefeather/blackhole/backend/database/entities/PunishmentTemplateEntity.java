package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishment_templates", indexes = @Index(columnList = "identifier"))
public class PunishmentTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    private String reason;

    private PunishType type;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    /**
     * Convert a PunishTemplate to a PunishmentTemplateEntity
     *
     * @param template the PunishTemplate to convert
     * @return the converted PunishmentTemplateEntity
     */
    public static PunishmentTemplateEntity toEntity(PunishTemplateDTO template) {
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
    public PunishTemplateDTO toDTO() {
        return new PunishTemplateDTO(
                this.metaData,
                this.reason,
                this.type,
                this.identifier
        );
    }
}
