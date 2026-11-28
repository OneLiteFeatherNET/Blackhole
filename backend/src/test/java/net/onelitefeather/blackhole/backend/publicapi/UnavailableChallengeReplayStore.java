package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.publicapi.challenge.ChallengeReplayStore;
import net.onelitefeather.blackhole.backend.publicapi.challenge.DragonflyChallengeReplayStore;

import java.time.Duration;

/**
 * Stands in for Dragonfly when the test asks for an outage: every reserve is unavailable, as a timeout or error would
 * make it. Active only with {@code test.replay-store=down}, so no other test class sees it. A real closed port cannot be
 * used, because the Redis connection is made when the context starts.
 */
@Singleton
@Requires(property = "test.replay-store", value = "down")
@Replaces(DragonflyChallengeReplayStore.class)
public class UnavailableChallengeReplayStore implements ChallengeReplayStore {

    @Override
    public Reservation reserve(String nonce, Duration remaining) {
        return Reservation.UNAVAILABLE;
    }
}
