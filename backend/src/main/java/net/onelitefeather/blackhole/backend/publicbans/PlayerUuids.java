package net.onelitefeather.blackhole.backend.publicbans;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reads the player UUID of an apply request: dashed or 32 hex digits, any case. Never echoes the input. */
public final class PlayerUuids {

    private static final Pattern DASHED = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern PLAIN = Pattern.compile("[0-9a-fA-F]{32}");

    private PlayerUuids() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** @return the UUID, or empty when the value is absent or not a UUID in either form */
    public static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = value.strip();
        if (DASHED.matcher(text).matches()) {
            return Optional.of(UUID.fromString(text));
        }
        if (PLAIN.matcher(text).matches()) {
            return Optional.of(new UUID(Long.parseUnsignedLong(text.substring(0, 16), 16),
                    Long.parseUnsignedLong(text.substring(16), 16)));
        }
        return Optional.empty();
    }
}
