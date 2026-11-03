package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicChallengeDTO;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.altcha.altcha.v2.Altcha;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter around the real ALTCHA library, with a cost of ten so a solve takes microseconds. The library reads the
 * system clock to check expiry, so these tests use the default test clock (2030, in the future) for a live challenge
 * and a clock in 2000 for an expired one; neither depends on the time of day.
 */
class AltchaChallengeVerifierTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static ChallengeIssuer issuer(Instant now) {
        PublicChallengeProperties properties = new PublicChallengeProperties(true, KEY, 10, "00", Duration.ofMinutes(5), Duration.ofMillis(500));
        return new ChallengeIssuer(new ChallengeKeyGuard(KEY), properties, new TestClock(now), new ChallengeMetrics(new SimpleMeterRegistry()));
    }

    private static final AltchaChallengeVerifier VERIFIER = new AltchaChallengeVerifier(new ChallengeKeyGuard(KEY));

    /** The library form of a challenge the portal received; the parameters are copied as they were issued. */
    private static Altcha.Challenge library(PublicChallengeDTO.Issued issued) {
        PublicChallengeDTO.Parameters p = issued.parameters();
        Altcha.ChallengeParameters parameters = new Altcha.ChallengeParameters(p.algorithm(), p.nonce(), p.salt(), p.cost(), p.keyLength(),
                p.keyPrefix(), null, null, null, p.expiresAt(), Map.<String, Object>of("purpose", p.data().purpose()));
        return new Altcha.Challenge(parameters, issued.signature());
    }

    /** The payload the widget sends back: the challenge as JSON and the solution the widget found. */
    private static String payload(Altcha.Challenge challenge, Altcha.Solution solution) {
        String json = "{\"challenge\":" + challenge.toJson() + ",\"solution\":{\"counter\":" + solution.counter()
                + ",\"derivedKey\":\"" + solution.derivedKey() + "\"}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Altcha.Solution solve(Altcha.Challenge challenge) throws Exception {
        return Altcha.solveChallenge(challenge, Altcha.pbkdf2());
    }

    private static PublicChallengeDTO.Issued issue(Instant now, String purpose) {
        return assertInstanceOf(PublicChallengeDTO.Issued.class, issuer(now).issue(purpose));
    }

    @Test
    void aSolvedChallengeIsAcceptedWithItsNonce() throws Exception {
        PublicChallengeDTO.Issued issued = issue(TestClock.START, "sign-in");
        Altcha.Challenge challenge = library(issued);

        ChallengeVerifier.Accepted accepted = VERIFIER.verify(payload(challenge, solve(challenge)), "sign-in")
                .orElseThrow(() -> new AssertionError("a solved challenge must verify"));

        assertEquals(issued.parameters().nonce(), accepted.nonce(), "the accepted solution names the challenge's nonce");
    }

    @Test
    void aSolutionForAnotherPurposeIsRefused() throws Exception {
        PublicChallengeDTO.Issued appeal = issue(TestClock.START, "appeal");
        Altcha.Challenge challenge = library(appeal);

        assertFalse(VERIFIER.verify(payload(challenge, solve(challenge)), "sign-in").isPresent(),
                "a solution for appeal does not open sign-in");
    }

    @Test
    void aChangedSignatureIsRefused() throws Exception {
        PublicChallengeDTO.Issued issued = issue(TestClock.START, "sign-in");
        Altcha.Challenge original = library(issued);
        Altcha.Solution solution = solve(original);
        char last = issued.signature().charAt(issued.signature().length() - 1);
        String forged = issued.signature().substring(0, issued.signature().length() - 1) + (last == '0' ? '1' : '0');
        Altcha.Challenge tampered = new Altcha.Challenge(original.parameters(), forged);

        assertFalse(VERIFIER.verify(payload(tampered, solution), "sign-in").isPresent(), "a challenge with a changed signature is refused");
    }

    @Test
    void aSolutionWithAWrongDerivedKeyIsRefused() throws Exception {
        PublicChallengeDTO.Issued issued = issue(TestClock.START, "sign-in");
        Altcha.Challenge challenge = library(issued);
        Altcha.Solution honest = solve(challenge);
        Altcha.Solution wrong = new Altcha.Solution(honest.counter(), "00".repeat(32), honest.time());

        assertFalse(VERIFIER.verify(payload(challenge, wrong), "sign-in").isPresent(), "a derived key the server did not compute is refused");
    }

    @Test
    void anExpiredChallengeIsRefused() throws Exception {
        PublicChallengeDTO.Issued issued = issue(Instant.parse("2000-01-01T00:00:00Z"), "sign-in");
        Altcha.Challenge challenge = library(issued);

        assertFalse(VERIFIER.verify(payload(challenge, solve(challenge)), "sign-in").isPresent(), "a challenge past its expiry is refused");
    }

    @Test
    void aMalformedPayloadIsRefused() {
        assertTrue(VERIFIER.verify("%%% not a payload %%%", "sign-in").isEmpty(), "anything that is not a payload is refused, never thrown");
    }
}
