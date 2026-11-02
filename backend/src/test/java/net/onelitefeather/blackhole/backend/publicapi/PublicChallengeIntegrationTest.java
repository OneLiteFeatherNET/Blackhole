package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The challenge switched on, against the real containers: issue, sign-in and filing with and without a solution. */
class PublicChallengeIntegrationTest extends PublicApiTestBase {

    @Override
    protected Map<String, Object> extraProperties() {
        Map<String, Object> properties = new HashMap<>(super.extraProperties());
        properties.put("blackhole.public.challenge.enabled", true);
        // A cheap difficulty keeps a solve in microseconds; the production default is measured separately.
        properties.put("blackhole.public.challenge.cost", 10);
        return properties;
    }

    private Raw challenge(String purpose) {
        return call(HttpMethod.GET, "/public/challenge?purpose=" + purpose, null, null);
    }

    private String solved(String purpose) throws Exception {
        Raw issued = challenge(purpose);
        assertEquals(200, issued.status(), issued.body());
        return ChallengeSolver.solve(issued.body());
    }

    private Raw signIn(String banId, String altcha) {
        Map<String, Object> body = new HashMap<>();
        body.put("banId", banId);
        if (altcha != null) {
            body.put("altcha", altcha);
        }
        return call(HttpMethod.POST, "/public/session", null, body);
    }

    private Raw fileAppeal(String token, String altcha) {
        Map<String, Object> body = new HashMap<>();
        body.put("conditionsVersion", 1);
        body.put("statement", "I was banned by mistake.");
        if (altcha != null) {
            body.put("altcha", altcha);
        }
        return playerPost("/public/appeal", token, body);
    }

    @Test
    void theChallengeForSignInIsIssuedWithNoStore() {
        Raw issued = challenge("sign-in");

        assertEquals(200, issued.status(), issued.body());
        assertEquals("no-store", issued.header("Cache-Control"), "a challenge is never cached");
        assertNotNull(issued.json().get("signature"), "the challenge carries its signature");
    }

    @Test
    void anUnknownPurposeIsABadRequest() {
        Raw issued = challenge("admin");

        assertEquals(400, issued.status(), issued.body());
        assertEquals("UNKNOWN_PURPOSE", issued.json().get("code"), "an unknown purpose is named, not echoed");
    }

    @Test
    void signInWithoutAChallengeIsRefused() {
        String punishment = punish("c1".repeat(64), "chal-none");

        Raw refused = signIn(banIdOf(punishment), null);

        assertEquals(403, refused.status(), refused.body());
        assertEquals("CHALLENGE_FAILED", refused.json().get("code"), "a request without a challenge gets CHALLENGE_FAILED");
    }

    @Test
    void signInWithAMalformedChallengeIsRefused() {
        String punishment = punish("c2".repeat(64), "chal-malformed");

        Raw refused = signIn(banIdOf(punishment), "not-a-payload");

        assertEquals(403, refused.status(), refused.body());
        assertEquals("CHALLENGE_FAILED", refused.json().get("code"), "a payload that does not verify gets CHALLENGE_FAILED");
    }

    @Test
    void signInWithAChallengeForAppealIsRefused() throws Exception {
        String punishment = punish("c3".repeat(64), "chal-purpose");

        Raw refused = signIn(banIdOf(punishment), solved("appeal"));

        assertEquals(403, refused.status(), refused.body());
        assertEquals("CHALLENGE_FAILED", refused.json().get("code"), "a solution for appeal does not open sign-in");
    }

    @Test
    void signInWithAGoodChallengeOpensASession() throws Exception {
        String punishment = punish("c4".repeat(64), "chal-good");

        Raw login = signIn(banIdOf(punishment), solved("sign-in"));

        assertEquals(200, login.status(), login.body());
        assertNotNull(login.json().get("token"), "the session token is returned once");
    }

    @Test
    void signInWithAGoodChallengeAndAWrongBanIdStaysUniform() throws Exception {
        Raw login = signIn("ZZZZZZZZ", solved("sign-in"));

        assertEquals(401, login.status(), login.body());
        assertEquals("INVALID_BAN_ID", login.json().get("code"), "a wrong Ban ID keeps the uniform answer after a valid challenge");
    }

    @Test
    void aSolutionOpensOnlyOneSignIn() throws Exception {
        String punishment = punish("c5".repeat(64), "chal-replay");
        String payload = solved("sign-in");
        assertEquals(200, signIn(banIdOf(punishment), payload).status(), "the first use opens the session");

        Raw replayed = signIn(banIdOf(punishment), payload);

        assertEquals(403, replayed.status(), replayed.body());
        assertEquals("CHALLENGE_FAILED", replayed.json().get("code"), "the same solution is refused the second time");
    }

    @Test
    void aWrongBanIdUsesUpTheSolution() throws Exception {
        String punishment = punish("c6".repeat(64), "chal-wrong-id");
        String payload = solved("sign-in");
        assertEquals(401, signIn("ZZZZZZZZ", payload).status(), "a wrong Ban ID is refused");

        Raw retry = signIn(banIdOf(punishment), payload);

        assertEquals(403, retry.status(), retry.body());
        assertEquals("CHALLENGE_FAILED", retry.json().get("code"), "the solution is spent by the wrong attempt");
    }

    @Test
    void filingWithoutAChallengeIsRefusedAndNothingIsFiled() throws Exception {
        String punishment = punish("c7".repeat(64), "chal-appeal-none");
        Raw login = signIn(banIdOf(punishment), solved("sign-in"));
        String token = (String) login.json().get("token");

        Raw refused = fileAppeal(token, null);

        assertEquals(403, refused.status(), refused.body());
        assertEquals("CHALLENGE_FAILED", refused.json().get("code"), "filing without a challenge is refused");
        Raw appeal = playerGet("/public/appeal", token);
        assertEquals(409, appeal.status(), "no appeal row was written: the player still has no appeal");
        assertEquals("NO_APPEAL", appeal.json().get("code"), "the answer names the missing appeal");
    }

    @Test
    void filingWithAGoodChallengeIsAccepted() throws Exception {
        String punishment = punish("c8".repeat(64), "chal-appeal-good");
        String token = (String) signIn(banIdOf(punishment), solved("sign-in")).json().get("token");

        Raw filed = fileAppeal(token, solved("appeal"));

        assertEquals(200, filed.status(), filed.body());
    }
}
