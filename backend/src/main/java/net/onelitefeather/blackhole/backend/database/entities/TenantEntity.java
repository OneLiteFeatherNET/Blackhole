package net.onelitefeather.blackhole.backend.database.entities;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.dto.TenantDTO;
import net.onelitefeather.blackhole.backend.dto.TenantStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Serdeable
@Entity
@Table(name = "tenants", indexes = @Index(columnList = "slug", unique = true))
public class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    private String name;

    private String slug;

    private TenantStatus status;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    /**
     * Convert a TenantDTO to a TenantEntity.
     *
     * @param tenant the TenantDTO to convert
     * @return the converted TenantEntity
     */
    public static TenantEntity toEntity(TenantDTO tenant) {
        return new TenantEntity(tenant.identifier(), tenant.name(), tenant.slug(), tenant.status(), tenant.metaData());
    }

    /**
     * Create a new TenantEntity.
     */
    public TenantEntity() {
        // Empty constructor for JPA
    }

    /**
     * Create a new TenantEntity.
     *
     * @param identifier the identifier of the tenant
     * @param name       the name of the tenant
     * @param slug       the unique slug of the tenant
     * @param status     the status of the tenant
     * @param metaData   the metadata of the tenant
     */
    public TenantEntity(UUID identifier, String name, String slug, TenantStatus status, Map<String, Object> metaData) {
        this.identifier = identifier;
        this.name = name;
        this.slug = slug;
        this.status = status;
        this.metaData = metaData;
    }

    /**
     * Get the identifier of the tenant.
     *
     * @return the identifier
     */
    public UUID getIdentifier() {
        return identifier;
    }

    /**
     * Get the name of the tenant.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Set the name of the tenant.
     *
     * @param name the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Get the unique slug of the tenant.
     *
     * @return the slug
     */
    public String getSlug() {
        return slug;
    }

    /**
     * Set the unique slug of the tenant.
     *
     * @param slug the slug to set
     */
    public void setSlug(String slug) {
        this.slug = slug;
    }

    /**
     * Get the status of the tenant.
     *
     * @return the status
     */
    public TenantStatus getStatus() {
        return status;
    }

    /**
     * Set the status of the tenant.
     *
     * @param status the status to set
     */
    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    /**
     * Get the metadata of the tenant.
     *
     * @return the metadata
     */
    public Map<String, Object> getMetaData() {
        return metaData;
    }

    /**
     * Convert a TenantEntity to a TenantDTO.
     *
     * @return the converted TenantDTO
     */
    public TenantDTO toDTO() {
        return new TenantDTO(this.identifier, this.name, this.slug, this.status, this.metaData);
    }
}
