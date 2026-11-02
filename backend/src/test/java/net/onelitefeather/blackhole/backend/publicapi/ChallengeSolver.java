package net.onelitefeather.blackhole.backend.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.altcha.altcha.v2.Altcha;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Plays the browser in a test: solves a challenge the backend issued with the real ALTCHA library and returns the
 * payload that goes into the {@code altcha} field. Costs are small (the tests set the cost to ten), so a solve is fast.
 */
final class ChallengeSolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChallengeSolver() {
    }

    /** The nonce of an issued challenge body. */
    static String nonceOf(String challengeJson) throws Exception {
        return JSON.readTree(challengeJson).get("parameters").get("nonce").asText();
    }

    /** The base64 payload of the challenge body, solved. */
    static String solve(String challengeJson) throws Exception {
        JsonNode root = JSON.readTree(challengeJson);
        JsonNode p = root.get("parameters");
        Altcha.ChallengeParameters parameters = new Altcha.ChallengeParameters(p.get("algorithm").asText(), p.get("nonce").asText(),
                p.get("salt").asText(), p.get("cost").asInt(), p.get("keyLength").asInt(), p.get("keyPrefix").asText(), null, null, null,
                p.get("expiresAt").asLong(), Map.<String, Object>of("purpose", p.get("data").get("purpose").asText()));
        Altcha.Challenge challenge = new Altcha.Challenge(parameters, root.get("signature").asText());
        Altcha.Solution solution = Altcha.solveChallenge(challenge, Altcha.pbkdf2());
        String json = "{\"challenge\":" + challengeJson + ",\"solution\":{\"counter\":" + solution.counter()
                + ",\"derivedKey\":\"" + solution.derivedKey() + "\"}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
