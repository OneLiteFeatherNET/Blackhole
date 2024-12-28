package net.onelitefeather.blackhole.api.utils;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

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

    private static final MessageDigest SHA_512;

    static {
        try {
            SHA_512 = MessageDigest.getInstance("SHA-512");
        } catch (Exception exception) {
            throw new RuntimeException("Failed to initialize SHA-512", exception);
        }
    }

    /**
     * Convert a UUID to a SHA-512 hash.
     *
     * @param uuid the UUID to convert
     * @return the SHA-512 hash
     */
    public static @NotNull String convertToSHA(@NotNull UUID uuid) {
        String uuidString = uuid.toString();
        // Update the digest with the UUID string
        SHA_512.update(uuidString.getBytes());

        // Get the digest bytes
        byte[] digestBytes = SHA_512.digest();

        // Convert the bytes to a string
        StringBuilder builder = new StringBuilder();
        for (byte digestByte : digestBytes) {
            builder.append(String.format("%02X", digestByte));
        }
        return builder.toString();
    }

    private UUIDConverter() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
