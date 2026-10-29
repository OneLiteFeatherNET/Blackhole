package net.onelitefeather.blackhole.backend.publicbans.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BanListAuthenticatorTest {

    @Test
    void aKnownClientWithItsPasswordIsAuthenticatedAsThatClient() {
        BanListAuthenticator authenticator = new BanListAuthenticator(
                Map.of("website", verifier("s3cret", new AtomicInteger())), verifier("unused", new AtomicInteger()));

        BanListAuthenticator.Outcome outcome = authenticator.authenticate(basic("website:s3cret"));

        assertEquals("website", outcome.client(), "the principal is the client name");
        assertEquals(BanListAuthenticator.Reason.OK, outcome.reason(), "a valid pair has the OK reason");
    }

    @Test
    void anUnknownClientRunsThePasswordCheckExactlyOnceAgainstTheDummy() {
        AtomicInteger dummyCalls = new AtomicInteger();
        AtomicInteger knownCalls = new AtomicInteger();
        BanListAuthenticator authenticator = new BanListAuthenticator(
                Map.of("website", verifier("s3cret", knownCalls)), verifier("unused", dummyCalls));

        BanListAuthenticator.Outcome outcome = authenticator.authenticate(basic("nobody:whatever"));

        assertNull(outcome.client(), "an unknown name is not authenticated");
        assertEquals(BanListAuthenticator.Reason.UNKNOWN_CLIENT, outcome.reason(), "the reason class is unknown-client");
        assertEquals(1, dummyCalls.get(), "the dummy hash is verified once, so the answer time does not reveal the name");
        assertEquals(0, knownCalls.get(), "no known client's hash is touched for an unknown name");
    }

    @Test
    void aWrongPasswordForAKnownClientIsRefusedWithTheBadPasswordReason() {
        BanListAuthenticator authenticator = new BanListAuthenticator(
                Map.of("website", verifier("s3cret", new AtomicInteger())), verifier("unused", new AtomicInteger()));

        BanListAuthenticator.Outcome outcome = authenticator.authenticate(basic("website:wrong"));

        assertNull(outcome.client(), "a wrong password is not authenticated");
        assertEquals(BanListAuthenticator.Reason.BAD_PASSWORD, outcome.reason(), "the reason class is bad-password");
    }

    @Test
    void aMissingHeaderIsRefusedWithoutAnyPasswordCheck() {
        AtomicInteger dummyCalls = new AtomicInteger();
        BanListAuthenticator authenticator = new BanListAuthenticator(Map.of(), verifier("unused", dummyCalls));

        BanListAuthenticator.Outcome outcome = authenticator.authenticate(null);

        assertEquals(BanListAuthenticator.Reason.MISSING, outcome.reason(), "no header is the missing reason");
        assertEquals(0, dummyCalls.get(), "no credential means nothing to verify");
    }

    /** A verifier that accepts one password and counts its calls, so tests assert on calls, not on time. */
    private static ClientHashVerifier verifier(String accepted, AtomicInteger calls) {
        return password -> {
            calls.incrementAndGet();
            return accepted.equals(password);
        };
    }

    private static String basic(String pair) {
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
