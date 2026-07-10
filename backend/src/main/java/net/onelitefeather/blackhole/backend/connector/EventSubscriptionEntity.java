package net.onelitefeather.blackhole.backend.connector;

import net.onelitefeather.blackhole.backend.connector.dto.EventSubscriptionDTO;
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
import jakarta.persistence.ManyToOne;
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
 * A connector's subscription to one or more domain event types. {@code signingSecret} is stored
 * as plaintext (unlike {@code ConnectorRegistrationEntity.oauth2ClientSecretHash}) since
 * computing an outbound HMAC signature requires the actual secret, not just a way to verify it.
 */
@Serdeable
@Entity
@Table(name = "event_subscriptions", indexes = {@Index(columnList = "identifier")})
public class EventSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    @ManyToOne
    private ConnectorRegistrationEntity connector;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_subscription_types", joinColumns = @JoinColumn(name = "subscription_identifier"))
    @Column(name = "event_type")
    private List<String> eventTypes = new ArrayList<>();

    private String deliveryUrl;

    private String signingSecret;

    private boolean active;

    private int failureCount;

    private Long lastAttemptAt;

    private Long lastSuccessAt;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    public EventSubscriptionEntity() {
        // Empty constructor for JPA
    }

    public EventSubscriptionEntity(
            ConnectorRegistrationEntity connector,
            List<String> eventTypes,
            String deliveryUrl,
            String signingSecret,
            boolean active,
            Map<String, Object> metaData
    ) {
        this.connector = connector;
        this.eventTypes = eventTypes;
        this.deliveryUrl = deliveryUrl;
        this.signingSecret = signingSecret;
        this.active = active;
        this.failureCount = 0;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public ConnectorRegistrationEntity getConnector() {
        return connector;
    }

    public List<String> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<String> eventTypes) {
        this.eventTypes = eventTypes;
    }

    public String getDeliveryUrl() {
        return deliveryUrl;
    }

    public void setDeliveryUrl(String deliveryUrl) {
        this.deliveryUrl = deliveryUrl;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public Long getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Long lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Long getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Long lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    /**
     * Converts to a DTO. Never includes the signing secret - it's shown once at creation time
     * only, the same one-time-reveal convention as the connector's client secret.
     */
    public EventSubscriptionDTO toDTO() {
        return new EventSubscriptionDTO(
                this.identifier,
                this.connector.getIdentifier(),
                this.eventTypes,
                this.deliveryUrl,
                this.active,
                this.failureCount,
                this.lastAttemptAt,
                this.lastSuccessAt,
                this.metaData
        );
    }
}
