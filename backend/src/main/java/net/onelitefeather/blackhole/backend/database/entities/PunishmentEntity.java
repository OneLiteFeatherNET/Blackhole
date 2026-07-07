package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.dto.PunishType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "punishments", indexes = {@Index(columnList = "identifier"), @Index(columnList = "tenantId")})
public class PunishmentEntity {

    @Id
    private String identifier;

    private UUID tenantId;

    private UUID source;

    /**
     * The type this punishment was actually applied as. Copied from the template's type at
     * creation time but tracked independently, since a template's type could change later
     * without retroactively changing how already-applied punishments are interpreted.
     */
    private PunishType type;

    /**
     * Optional scope identifier (e.g. an event or community) this punishment is restricted to.
     * {@code null} means the punishment applies network-wide.
     */
    private String scope;

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
                entry.tenantId(),
                entry.source(),
                entry.type(),
                entry.scope(),
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
     * @param tenantId   the tenant the punishment belongs to
     * @param source     the source of the punishment
     * @param type       the type the punishment was actually applied as
     * @param scope      the optional scope (e.g. event/community) this punishment is restricted to
     * @param template   the template used for the punishment
     * @param metaData   the metadata of the punishment
     */
    public PunishmentEntity(String identifier, UUID tenantId, UUID source, PunishType type, String scope, PunishmentTemplateEntity template, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.tenantId = tenantId;
        this.source = source;
        this.type = type;
        this.scope = scope;
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
     * Get the tenant the punishment belongs to.
     *
     * @return the tenant identifier
     */
    public UUID getTenantId() {
        return tenantId;
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
     * Get the type this punishment was actually applied as.
     *
     * @return the type
     */
    public PunishType getType() {
        return type;
    }

    /**
     * Get the optional scope (e.g. event/community) this punishment is restricted to.
     *
     * @return the scope, or {@code null} for a network-wide punishment
     */
    public String getScope() {
        return scope;
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
                this.tenantId,
                this.source,
                this.type,
                this.scope,
                this.template.toDTO(),
                this.metaData
        );
    }
}
