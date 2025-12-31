package net.onelitefeather.blackhole.velocity.utils;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * A utility class to convert UUIDs to SHA-512 hashes.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
@ApiStatus.Internal
public final class UUIDConverter {

    /**
     * Convert a UUID to a SHA-512 hash.
     *
     * @param uuid the UUID to convert
     * @return the SHA-512 hash
     */
    public static @NotNull String convertToSHA(@NotNull UUID uuid) {
        String uuidString = uuid.toString();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            // Update the digest with the UUID string
            digest.update(uuidString.getBytes());

            // Get the digest bytes
            byte[] digestBytes = digest.digest();
            return String.format("%0128x", new BigInteger(1, digestBytes));
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert UUID to SHA-512", e);
        }
    }

    private UUIDConverter() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
