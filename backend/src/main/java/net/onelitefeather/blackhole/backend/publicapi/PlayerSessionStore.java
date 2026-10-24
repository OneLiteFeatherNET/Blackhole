package net.onelitefeather.blackhole.backend.publicapi;

import java.util.Optional;

/**
 * Where player sessions live. Only the SHA-256 of a token passes through here; the time is always passed
 * in by the caller (the application clock), so expiry never depends on the database clock.
 */
public interface PlayerSessionStore {

    /**
     * A live session as the store answers it.
     *
     * @param scope     the stored scope, or {@link PlayerScope#LIMITED} when the Ban ID is marked leaked now
     * @param expiresAt the absolute end of the session, epoch millis
     */
    record Live(String punishmentIdentifier, PlayerScope scope, String csrf, long expiresAt) {
    }

    void insert(String tokenHash, String punishmentIdentifier, PlayerScope scope, String csrf, long now, long expiresAt);

    /**
     * Finds a session that is neither idle nor past its absolute limit and, in the same statement, moves its
     * last-seen time to {@code now}.
     */
    Optional<Live> touch(String tokenHash, long now, long idleMillis);

    /** Deletes one session; whether there was one. */
    boolean delete(String tokenHash);

    /** Deletes every session past its idle or absolute limit; how many. */
    int sweep(long now, long idleMillis);
}
