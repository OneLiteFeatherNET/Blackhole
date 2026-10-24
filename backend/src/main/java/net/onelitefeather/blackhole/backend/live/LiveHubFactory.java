package net.onelitefeather.blackhole.backend.live;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/** Builds the one live hub of this replica from its ports and limits. */
@Factory
public class LiveHubFactory {

    @Singleton
    public LiveHub liveHub(ChangeSignalTransport transport, LiveSequence sequence, LiveSessions sessions, LiveScheduler scheduler,
                           LiveProperties properties) {
        return new LiveHub(transport, sequence, sessions, scheduler,
                new LiveHub.Limits(properties.getMaxStreams(), properties.getKeepAlive(), properties.getMaxLifetime()));
    }
}
