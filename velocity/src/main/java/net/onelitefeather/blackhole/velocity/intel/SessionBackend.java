package net.onelitefeather.blackhole.velocity.intel;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.SessionStartRequest;
import net.onelitefeather.blackhole.client.model.SessionUpdateRequest;

/**
 * The two session calls the proxy makes: the start of a login and the update of an open session.
 * Blocking; the {@link SessionReporter} calls it off the login thread.
 */
public interface SessionBackend {

    /**
     * @param request the login attempt, after the ban check
     * @throws ApiException if the backend refused or did not answer
     */
    void record(SessionStartRequest request) throws ApiException;

    /**
     * @param sessionId the session the update belongs to
     * @param request   the client brand, or the disconnect
     * @throws ApiException if the backend refused or did not answer
     */
    void update(String sessionId, SessionUpdateRequest request) throws ApiException;
}
