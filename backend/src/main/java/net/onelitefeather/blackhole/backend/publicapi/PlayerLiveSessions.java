package net.onelitefeather.blackhole.backend.publicapi;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.live.LiveSessions;

import java.time.Clock;

/** Whether a player session behind a live stream is still alive, read on the session's own idle and absolute limits. */
@Singleton
public class PlayerLiveSessions implements LiveSessions {

    private final PlayerSessionStore store;
    private final PlayerSessionProperties properties;
    private final Clock clock;

    public PlayerLiveSessions(PlayerSessionStore store, PlayerSessionProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean alive(String sessionKey) {
        return this.store.alive(sessionKey, this.clock.millis(), this.properties.idle().toMillis());
    }
}
