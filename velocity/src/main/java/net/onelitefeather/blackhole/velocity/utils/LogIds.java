package net.onelitefeather.blackhole.velocity.utils;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * How a player is named in a log line: the start of their owner hash. It is enough to correlate the lines of one
 * player, and it does not reveal the name, which the logs of a network must never hold.
 */
public final class LogIds {

    private static final int LENGTH = 8;

    private LogIds() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * @param ownerHash the owner hash of a player
     * @return its first characters
     */
    public static @NotNull String of(@NotNull String ownerHash) {
        return ownerHash.length() <= LENGTH ? ownerHash : ownerHash.substring(0, LENGTH);
    }

    /**
     * @param uuid the player's UUID
     * @return the first characters of the owner hash of the UUID
     */
    public static @NotNull String of(@NotNull UUID uuid) {
        return of(UUIDConverter.convertToSHA(uuid));
    }
}
