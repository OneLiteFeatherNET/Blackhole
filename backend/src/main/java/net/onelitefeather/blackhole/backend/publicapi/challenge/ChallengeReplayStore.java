package net.onelitefeather.blackhole.backend.publicapi.challenge;

import java.time.Duration;

/** Remembers the solutions already used, so each one is accepted once. */
public interface ChallengeReplayStore {

    /**
     * Reserves a solution for the rest of its lifetime.
     *
     * @param nonce     the challenge's identifier
     * @param remaining how long the challenge still has to run
     * @return {@link Reservation#RESERVED} when this call took it, {@link Reservation#REPLAYED} when it was used before,
     *         {@link Reservation#UNAVAILABLE} when the store did not answer in time
     */
    Reservation reserve(String nonce, Duration remaining);

    /** The outcome of a reserve. */
    enum Reservation {
        RESERVED, REPLAYED, UNAVAILABLE
    }
}
