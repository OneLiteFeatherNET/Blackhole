package net.onelitefeather.blackhole.backend.banid;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The keyed parts of the Ban ID: the lookup hash (HMAC-SHA256) and the display encryption
 * (AES-256-GCM, bound to the punishment identifier). Both keys are derived from the one secret with
 * HKDF-SHA256 and different {@code info} labels, so neither can stand in for the other.
 */
@Singleton
public class BanIdCrypto {

    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] hmacKey;
    private final SecretKeySpec aesKey;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public BanIdCrypto(BanIdProperties properties) {
        this(secretOf(properties.key()));
    }

    BanIdCrypto(byte[] secret) {
        byte[] prk = hmac(new byte[KEY_BYTES], secret);
        this.hmacKey = hmac(prk, bytes("blackhole-banid-lookup-v1\u0001"));
        this.aesKey = new SecretKeySpec(hmac(prk, bytes("blackhole-banid-display-v1\u0001")), "AES");
    }

    /** The configured secret; unset (only allowed in dev and test, see {@link BanIdKeyGuard}) means a random one for this run. */
    private static byte[] secretOf(String configured) {
        if (configured != null && !configured.isBlank()) {
            return decode(configured);
        }
        byte[] ephemeral = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(ephemeral);
        LoggerFactory.getLogger(BanIdCrypto.class).warn("BLACKHOLE_BANID_KEY is not set: using a random Ban ID key for this run only. "
                + "Every Ban ID issued now becomes unreadable and unusable after a restart. Set the variable for anything longer-lived.");
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
            throw new IllegalStateException("BLACKHOLE_BANID_KEY must be base64 of exactly " + KEY_BYTES + " bytes");
        }
        if (secret.length != KEY_BYTES) {
            throw new IllegalStateException("BLACKHOLE_BANID_KEY must be base64 of exactly " + KEY_BYTES + " bytes");
        }
        return secret;
    }

    /** The lookup key of a normalised Ban ID. */
    public byte[] hash(String normalizedBanId) {
        return hmac(this.hmacKey, bytes(normalizedBanId));
    }

    /** {@code nonce || ciphertext+tag}, with a fresh random nonce and the punishment identifier as associated data. */
    public byte[] encrypt(String normalizedBanId, String punishmentIdentifier) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            this.random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, this.aesKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(bytes(punishmentIdentifier));
            byte[] sealed = cipher.doFinal(bytes(normalizedBanId));
            byte[] blob = Arrays.copyOf(nonce, NONCE_BYTES + sealed.length);
            System.arraycopy(sealed, 0, blob, NONCE_BYTES, sealed.length);
            return blob;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available");
        }
    }

    /**
     * @return the Ban ID, or {@code null} if the blob is not the encryption of a Ban ID of that
     *         punishment under this key (another key, another punishment, or tampering)
     */
    public String decrypt(byte[] blob, String punishmentIdentifier) {
        if (blob == null || blob.length <= NONCE_BYTES) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, this.aesKey, new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES));
            cipher.updateAAD(bytes(punishmentIdentifier));
            return new String(cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available");
        }
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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
