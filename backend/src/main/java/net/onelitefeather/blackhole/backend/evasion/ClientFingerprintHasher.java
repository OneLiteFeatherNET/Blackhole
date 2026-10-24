package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.context.env.Environment;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/**
 * The keyed part of the client fingerprint: HMAC-SHA256 of the canonical trait string under a key that
 * HKDF-SHA256 derives from {@code BLACKHOLE_FINGERPRINT_KEY}. The key is a key of its own: a leak of the
 * IP salt or the Ban ID key does not unlock the fingerprints and each can be rotated on its own schedule.
 *
 * <p><b>Rotation:</b> treat the key as effectively unrotatable in place. A new key makes old and new
 * fingerprints uncorrelatable, so a rotation is a new {@link ClientFingerprintTraits#SCHEME scheme} and key
 * pair with a documented overlap, not an in-place change.</p>
 *
 * <p>Unset: outside {@code dev} and {@code test} fingerprinting is off ({@link #isEnabled()} is false); in a
 * local environment a random key for this run is used and a warning says so.</p>
 */
@Singleton
public class ClientFingerprintHasher {

    static final int KEY_BYTES = 32;
    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of(Environment.DEVELOPMENT, Environment.TEST);

    private final byte[] key;

    @Inject
    public ClientFingerprintHasher(ClientFingerprintProperties properties, Environment environment) {
        this(properties.fingerprintKey(), environment.getActiveNames());
    }

    ClientFingerprintHasher(String configuredKey, Set<String> activeEnvironments) {
        this(secretOf(configuredKey, activeEnvironments.stream().anyMatch(LOCAL_ENVIRONMENTS::contains)));
    }

    /** @param secret the 32 byte secret, or {@code null} for a hasher that is off */
    ClientFingerprintHasher(byte[] secret) {
        this.key = secret == null ? null : derive(secret);
    }

    private static byte[] secretOf(String configured, boolean local) {
        if (configured != null && !configured.isBlank()) {
            return decode(configured);
        }
        if (!local) {
            LoggerFactory.getLogger(ClientFingerprintHasher.class).warn("BLACKHOLE_FINGERPRINT_KEY is not set: client fingerprinting is off.");
            return null;
        }
        byte[] ephemeral = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(ephemeral);
        LoggerFactory.getLogger(ClientFingerprintHasher.class).warn("BLACKHOLE_FINGERPRINT_KEY is not set: using a random fingerprint key for this run only. "
                + "Fingerprints stored now cannot be correlated with those of the next run. Set the variable for anything longer-lived.");
        return ephemeral;
    }

    /**
     * @throws IllegalStateException if the value is not base64 of exactly 32 bytes (the value itself is never named)
     */
    static byte[] decode(String base64) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(base64 == null ? "" : base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BLACKHOLE_FINGERPRINT_KEY must be base64 of exactly " + KEY_BYTES + " bytes");
        }
        if (secret.length != KEY_BYTES) {
            throw new IllegalStateException("BLACKHOLE_FINGERPRINT_KEY must be base64 of exactly " + KEY_BYTES + " bytes");
        }
        return secret;
    }

    /** Whether fingerprinting is on: a key is configured, or a local environment generated one. */
    public boolean isEnabled() {
        return this.key != null;
    }

    /**
     * @param canonical the canonical trait string of {@link ClientFingerprintTraits#canonical()}
     * @return 64 lower-case hex characters
     * @throws IllegalStateException if fingerprinting is off - callers check {@link #isEnabled()} first
     */
    public String fingerprint(String canonical) {
        if (this.key == null) {
            throw new IllegalStateException("client fingerprinting is not configured");
        }
        return HexFormat.of().formatHex(hmac(this.key, canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] derive(byte[] secret) {
        byte[] prk = hmac(new byte[KEY_BYTES], secret);
        return hmac(prk, ("blackhole-client-fingerprint-" + ClientFingerprintTraits.SCHEME + "\u0001").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available");
        }
    }
}
