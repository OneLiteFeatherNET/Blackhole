package net.onelitefeather.blackhole.backend.analytics;

import java.util.Optional;

/** Starts refresh runs. Empty when another replica holds the refresh lock: the run then does nothing. */
public interface RefreshSessions {

    Optional<RefreshSession> tryStart();
}
