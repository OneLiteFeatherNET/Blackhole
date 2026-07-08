package net.onelitefeather.blackhole.backend.imports;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Hashes a raw player UUID to the same SHA-512 representation Velocity's own
 * {@code UUIDConverter} produces client-side. Only needed server-side for bulk imports (e.g. the
 * vanilla ban-list import), where the backend receives raw UUIDs directly instead of an
 * already-hashed owner from a client request.
 */
public final class UUIDHasher {

    private UUIDHasher() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    public static String hash(UUID uuid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(uuid.toString().getBytes());
            byte[] digestBytes = digest.digest();
            return String.format("%0128x", new BigInteger(1, digestBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash UUID to SHA-512", e);
        }
    }
}
