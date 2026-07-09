package net.onelitefeather.blackhole.backend.connector;

import net.onelitefeather.blackhole.backend.connector.dto.ConnectorRegistrationDTO;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A registered third-party connector (e.g. a Discord bot, an anticheat system). Represents
 * "one external system" generically - nothing here is specific to any one integration, matching
 * the roadmap's explicit goal of no hardcoded Discord/Vulcan/etc. code.
 */
@Serdeable
@Entity
@Table(name = "connector_registrations", indexes = {
        @Index(columnList = "identifier"),
        @Index(columnList = "oauth2ClientId", unique = true)
})
public class ConnectorRegistrationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    private String name;

    private String oauth2ClientId;

    private String oauth2ClientSecretHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "connector_scopes", joinColumns = @JoinColumn(name = "connector_identifier"))
    @Column(name = "scope")
    private List<String> scopes = new ArrayList<>();

    private ConnectorStatus status;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    public ConnectorRegistrationEntity() {
        // Empty constructor for JPA
    }

    public ConnectorRegistrationEntity(
            String name,
            String oauth2ClientId,
            String oauth2ClientSecretHash,
            List<String> scopes,
            ConnectorStatus status,
            Map<String, Object> metaData
    ) {
        this.name = name;
        this.oauth2ClientId = oauth2ClientId;
        this.oauth2ClientSecretHash = oauth2ClientSecretHash;
        this.scopes = scopes;
        this.status = status;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOauth2ClientId() {
        return oauth2ClientId;
    }

    public String getOauth2ClientSecretHash() {
        return oauth2ClientSecretHash;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public ConnectorStatus getStatus() {
        return status;
    }

    public void setStatus(ConnectorStatus status) {
        this.status = status;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    /**
     * Converts to a DTO. Never includes the client secret (or its hash) - callers only ever see
     * the plaintext secret once, at creation time, in the registration response.
     */
    public ConnectorRegistrationDTO toDTO() {
        return new ConnectorRegistrationDTO(
                this.identifier,
                this.name,
                this.oauth2ClientId,
                this.scopes,
                this.status,
                this.metaData
        );
    }
}
