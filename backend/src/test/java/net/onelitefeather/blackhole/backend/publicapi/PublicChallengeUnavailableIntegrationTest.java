package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Dragonfly does not answer: the reserve is replaced by {@link UnavailableChallengeReplayStore}. Issuing still works (it
 * needs no store); sign-in with a good solution fails closed with 503 and opens no session.
 */
class PublicChallengeUnavailableIntegrationTest extends PublicApiTestBase {

    @Override
    protected Map<String, Object> extraProperties() {
        Map<String, Object> properties = new HashMap<>(super.extraProperties());
        properties.put("blackhole.public.challenge.enabled", true);
        properties.put("blackhole.public.challenge.cost", 10);
        properties.put("test.replay-store", "down");
        return properties;
    }

    @Test
    void aChallengeIsIssuedWhileTheReplayStoreIsDown() {
        Raw issued = call(HttpMethod.GET, "/public/challenge?purpose=sign-in", null, null);

        assertEquals(200, issued.status(), issued.body());
    }

    @Test
    void signInFailsClosedWithoutASessionWhileTheReplayStoreIsDown() throws Exception {
        Raw issued = call(HttpMethod.GET, "/public/challenge?purpose=sign-in", null, null);
        String payload = ChallengeSolver.solve(issued.body());
        Map<String, Object> body = new HashMap<>();
        body.put("banId", "ZZZZZZZZ");
        body.put("altcha", payload);

        Raw login = call(HttpMethod.POST, "/public/session", null, body);

        assertEquals(503, login.status(), login.body());
        assertEquals("UNAVAILABLE", login.json().get("code"), "the answer names the outage");
        assertNull(login.json().get("token"), "no session is created");
    }
}
