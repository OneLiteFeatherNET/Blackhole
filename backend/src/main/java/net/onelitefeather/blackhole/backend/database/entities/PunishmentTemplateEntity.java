package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.dto.PunishType;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishment_templates", indexes = {@Index(columnList = "identifier"), @Index(columnList = "tenantId")})
public class PunishmentTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    private UUID tenantId;

    private String reason;

    private PunishType type;

    /**
     * The Elo delta applied to the offender when this template is applied via
     * {@code PunishmentApplicationService.apply(...)} - {@code 0} means this template has no
     * Elo effect (the default for pre-existing templates, and the recommended value for
     * permanent-ban templates, since dropping Elo further after a permanent ban is moot).
     */
    private int eloDelta;

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
        return new PunishmentTemplateEntity(template.identifier(), template.tenantId(), template.reason(), template.type(), template.eloDelta(), template.metaData());
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
     * @param tenantId   the tenant the template belongs to
     * @param reason     the reason of the template
     * @param type       the type of the template
     * @param eloDelta   the Elo delta applied when this template is applied ({@code 0} = no effect)
     * @param metaData   the metadata of the template
     */
    public PunishmentTemplateEntity(UUID identifier, UUID tenantId, String reason, PunishType type, int eloDelta, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.tenantId = tenantId;
        this.reason = reason;
        this.type = type;
        this.eloDelta = eloDelta;
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
     * Get the tenant the template belongs to.
     *
     * @return the tenant identifier
     */
    public UUID getTenantId() {
        return tenantId;
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
     * Get the Elo delta applied when this template is applied.
     *
     * @return the Elo delta ({@code 0} = no effect)
     */
    public int getEloDelta() {
        return eloDelta;
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
                this.tenantId,
                this.metaData,
                this.reason,
                this.type,
                this.eloDelta,
                this.identifier
        );
    }
}
