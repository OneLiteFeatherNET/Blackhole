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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One (tenant, token, owner) sighting for ban-evasion detection. {@code token} is
 * HMAC-SHA512(ip, server-held salt) - a keyed hash, not a plain hash of the IP alone, since the
 * IPv4 address space is small enough to brute-force an unsalted hash trivially. This table is
 * itself personal data (see {@code IpCorrelationRetentionSweeper} for its retention window).
 */
@Serdeable
@Entity
@Table(name = "ip_correlation_tokens", indexes = {
        @Index(columnList = "identifier"),
        @Index(columnList = "tenantId"),
        @Index(columnList = "token")
})
public class IpCorrelationTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    private UUID tenantId;

    private String token;

    private String ownerHash;

    private long firstSeen;

    private long lastSeen;

    private int occurrenceCount;

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    public IpCorrelationTokenEntity() {
        // Empty constructor for JPA
    }

    public IpCorrelationTokenEntity(UUID tenantId, String token, String ownerHash, long firstSeen, long lastSeen, int occurrenceCount, Map<String, Object> metaData) {
        this.tenantId = tenantId;
        this.token = token;
        this.ownerHash = ownerHash;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.occurrenceCount = occurrenceCount;
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getToken() {
        return token;
    }

    public String getOwnerHash() {
        return ownerHash;
    }

    public long getFirstSeen() {
        return firstSeen;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(int occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }
}
