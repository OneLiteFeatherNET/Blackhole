package net.onelitefeather.blackhole.backend.banid;

import java.util.List;
import java.util.Optional;

/** Where Ban IDs live: the hash and ciphertext columns of {@code punishments}. Plain Ban IDs never pass through here. */
public interface BanIdStore {

    /** What {@link #assign} did. */
    enum Assignment {
        /** The punishment now has this Ban ID. */
        ASSIGNED,
        /** The punishment already had a Ban ID (or does not exist); nothing changed. */
        UNCHANGED,
        /** Another punishment has this hash; draw a new candidate. */
        COLLISION
    }

    /** Sets the first Ban ID of a punishment that has none. Idempotent: never overwrites. */
    Assignment assign(String punishmentIdentifier, byte[] hash, byte[] encrypted);

    /** The punishment with the given lookup hash. */
    Optional<String> identifierByHash(byte[] hash);

    /** The ciphertext of a punishment's Ban ID, empty if it has none yet. */
    Optional<byte[]> encrypted(String punishmentIdentifier);

    /** Whether any punishment has this legacy short id key (eight upper case hex characters). */
    boolean legacyShortIdExists(String shortIdKey);

    /** Up to {@code limit} punishments without a Ban ID. */
    List<String> withoutBanId(int limit);

    /** A punishment that has a Ban ID: its ciphertext and generation, never the plain value. */
    record Held(String identifier, byte[] encrypted, int generation) {
    }

    /** Up to {@code limit} punishments with a Ban ID whose identifier sorts after {@code after} ({@code null} for the first page), in identifier order. */
    List<Held> heldAfter(String after, int limit);

    /** How many punishments still have no Ban ID. */
    long countWithoutBanId();
}
