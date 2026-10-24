package net.onelitefeather.blackhole.backend.publicapi.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.live.LiveAudience;
import net.onelitefeather.blackhole.backend.live.LiveHub;
import net.onelitefeather.blackhole.backend.live.LiveStreams;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.security.PlayerSession;

/** Opens the player's live stream of the session's own conversation. A leaked Ban ID gets no stream. */
@Singleton
public class PublicLiveService {

    private final LiveStreams streams;

    public PublicLiveService(LiveStreams streams) {
        this.streams = streams;
    }

    /**
     * @param lastEventId the {@code Last-Event-ID} header, or {@code null}
     * @return the opened stream, {@code Forbidden} for a leaked Ban ID, or {@code Busy} when the replica is full
     */
    public LiveStreams.Opening open(PlayerSession session, @Nullable String lastEventId) {
        if (session.scope() == PlayerScope.LIMITED) {
            return new LiveStreams.Opening.Forbidden();
        }
        return this.streams.open(new LiveHub.Subscription(session.punishmentIdentifier(), LiveAudience.PLAYER, session.tokenHash()),
                lastEventId);
    }
}
