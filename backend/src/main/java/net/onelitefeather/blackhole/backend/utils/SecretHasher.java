package net.onelitefeather.blackhole.backend.utils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashes an arbitrary secret (e.g. a connector's OAuth2 client secret) the same way
 * {@link UUIDHasher} hashes player UUIDs - SHA-512, never store the plaintext.
 */
public final class SecretHasher {

    private SecretHasher() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            byte[] digestBytes = digest.digest();
            return String.format("%0128x", new BigInteger(1, digestBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash secret to SHA-512", e);
        }
    }
}
