package net.onelitefeather.blackhole.backend.utils;

import jakarta.validation.constraints.NotNull;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

/**
 * Utility class for generating unique IDs.
 *
 * @since 1.0.0
 * @version 1.0.0
 * @see java.util.UUID
 * @author TheMeinerLP
 */
public final class IdGenerator {

    private IdGenerator() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Generates a unique ID that's limited to 22 characters.
     *
     * @return The generated ID.
     */
    public static @NotNull String generateId() {
        return generateId(UUID.randomUUID());
    }

    /**
     * Generates a unique ID that's limited to 22 characters based on the given UUID.
     *
     * @return The generated ID.
     */
    public static @NotNull String generateId(@NotNull UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        byte[] uuidBytes = bb.array();
        String base64 = Base64.getUrlEncoder().encodeToString(uuidBytes);
        return base64.replace("=", "");
    }
}
