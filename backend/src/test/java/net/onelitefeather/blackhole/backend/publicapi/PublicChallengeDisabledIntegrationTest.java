package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The switch off, as dev and test run it: the challenge answers enabled false and sign-in needs no solution. */
class PublicChallengeDisabledIntegrationTest extends PublicApiTestBase {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.public.challenge.enabled", false);
    }

    @Test
    void theChallengeAnswersEnabledFalseWhenTheCheckIsOff() {
        Raw answer = call(HttpMethod.GET, "/public/challenge?purpose=sign-in", null, null);

        assertEquals(200, answer.status(), answer.body());
        assertEquals(false, answer.json().get("enabled"), "the widget is not shown when the check is off");
    }

    @Test
    void signInWithoutAChallengeIsAcceptedWhenTheCheckIsOff() {
        String punishment = punish("d1".repeat(64), "chal-off");

        Raw login = login(banIdOf(punishment));

        assertEquals(200, login.status(), login.body());
    }
}
