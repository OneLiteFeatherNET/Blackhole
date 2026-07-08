package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.IpCorrelationTokenEntity;
import net.onelitefeather.blackhole.backend.database.repository.IpCorrelationTokenRepository;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Privacy-preserving ban-evasion detection. A raw IP is never persisted - only
 * HMAC-SHA512(ip, salt), a keyed hash a moderator/connector can correlate but never reverse.
 *
 * <p><b>Salt rotation policy (documented per the roadmap's explicit requirement):</b> the salt
 * ({@code blackhole.evasion.ip-salt}) must stay stable to keep correlating logins over time.
 * Rotating it is a deliberate privacy/utility tradeoff: every token computed under the old salt
 * becomes uncorrelatable with new ones, which quietly resets evasion detection across that
 * boundary. Rotate only when the salt itself is suspected compromised, not routinely.</p>
 */
@Singleton
public class IpCorrelationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IpCorrelationService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA512";

    private final IpCorrelationTokenRepository repository;
    private final DomainEventPublisher eventPublisher;
    private final String salt;
    private final long detectionWindowMillis;

    public IpCorrelationService(
            IpCorrelationTokenRepository repository,
            DomainEventPublisher eventPublisher,
            @Value("${blackhole.evasion.ip-salt:}") String salt,
            @Value("${blackhole.evasion.detection-window-days:7}") int detectionWindowDays
    ) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.salt = salt;
        this.detectionWindowMillis = Duration.ofDays(detectionWindowDays).toMillis();
    }

    public boolean isConfigured() {
        return !this.salt.isBlank();
    }

    /**
     * Records a login sighting and checks for evasion. No-op-safe to call repeatedly for the
     * same owner/IP - only updates {@code lastSeen}/{@code occurrenceCount} after the first time.
     *
     * @throws IllegalStateException if {@link #isConfigured()} is false - callers must check first
     */
    public void recordLogin(String ownerHash, String rawIp) {
        if (!isConfigured()) {
            throw new IllegalStateException("blackhole.evasion.ip-salt is not configured");
        }

        String token = hmacSha512(rawIp, this.salt);
        long now = System.currentTimeMillis();

        IpCorrelationTokenEntity existing = this.repository.findByTokenAndOwnerHash(token, ownerHash).orElse(null);
        if (existing == null) {
            this.repository.save(new IpCorrelationTokenEntity(token, ownerHash, now, now, 1, new HashMap<>()));
        } else {
            existing.setLastSeen(now);
            existing.setOccurrenceCount(existing.getOccurrenceCount() + 1);
            this.repository.update(existing);
        }

        checkEvasion(token, now);
    }

    private void checkEvasion(String token, long now) {
        long windowStart = now - this.detectionWindowMillis;
        List<IpCorrelationTokenEntity> sightings = this.repository.findByTokenAndLastSeenGreaterThanEquals(token, windowStart);
        List<String> distinctOwners = sightings.stream().map(IpCorrelationTokenEntity::getOwnerHash).distinct().toList();
        if (distinctOwners.size() <= 1) {
            return;
        }

        LOGGER.warn("Ban-evasion signal: {} distinct owners share a token", distinctOwners.size());
        this.eventPublisher.publish("evasion.detected", Map.of(
                "token", token,
                "owners", distinctOwners
        ));
    }

    private static String hmacSha512(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(result.length * 2);
            for (byte b : result) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to compute HMAC-SHA512", e);
        }
    }
}
