package net.onelitefeather.blackhole.backend.publicbans;

import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The keyed values of the public ban list: the entry hash ({@code hash} of a listed sanction) and the signature of
 * the list cursor. Both keys are derived from {@code BLACKHOLE_PUBLIC_BAN_KEY} with HKDF-SHA256 and different labels.
 *
 * <p>The player hash ({@code playerHash}) is not computed here: the database derives it in the generated column
 * {@code player_hash_v1}, the single implementation of that scheme.
 */
public final class PublicBanHasher {

    static final int KEY_BYTES = 32;
    private static final int ENTRY_HASH_BYTES = 16;
    private static final String ENTRY_MESSAGE_LABEL = "public-ban-entry-v1";

    private final byte[] entryKey;
    private final byte[] cursorKey;

    /**
     * @param configuredKey base64 of 32 random bytes, or {@code null} or blank for a random key for this run only
     * @throws IllegalStateException if the configured value is not base64 of 32 bytes (the value is never named)
     */
    public PublicBanHasher(String configuredKey) {
        byte[] secret = secretOf(configuredKey);
        byte[] prk = hmac(new byte[KEY_BYTES], secret);
        this.entryKey = hmac(prk, bytes("blackhole-public-ban-entry-v1\u0001"));
        this.cursorKey = hmac(prk, bytes("blackhole-public-ban-cursor-v1\u0001"));
    }

    /** The public hash of a listed entry: 128 bits of a keyed HMAC of the internal identifier, base64url without padding. */
    public String entryHash(String identifier) {
        byte[] message = bytes(ENTRY_MESSAGE_LABEL + "\u0001" + identifier);
        byte[] tag = Arrays.copyOf(hmac(this.entryKey, message), ENTRY_HASH_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
    }

    /** The base64url signature of a cursor payload. */
    public String signCursor(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(this.cursorKey, bytes(payload)));
    }

    private static byte[] secretOf(String configured) {
        if (configured != null && !configured.isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(configured.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("BLACKHOLE_PUBLIC_BAN_KEY must be base64 of exactly " + KEY_BYTES + " bytes");
            }
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException("BLACKHOLE_PUBLIC_BAN_KEY must be base64 of exactly " + KEY_BYTES + " bytes");
            }
            return decoded;
        }
        byte[] ephemeral = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(ephemeral);
        LoggerFactory.getLogger(PublicBanHasher.class).warn("BLACKHOLE_PUBLIC_BAN_KEY is not set: public entry hashes use a random key "
                + "for this run only and change at every restart.");
        return ephemeral;
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
