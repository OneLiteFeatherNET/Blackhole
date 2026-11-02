package net.onelitefeather.blackhole.backend.publicapi.challenge;

import java.time.Instant;
import java.util.Optional;

/**
 * Checks a solved challenge: the signature, the expiry, the key prefix and the purpose it was issued for. A port, so
 * the gate is tested with a fake and never depends on the library, which reads the system clock.
 */
public interface ChallengeVerifier {

    /**
     * @param payload the base64 payload the widget sent: the challenge and its solution
     * @param purpose the purpose the route expects, {@code sign-in} or {@code appeal}
     * @return the accepted solution, or empty for anything that is not a valid solution for this purpose; never throws
     */
    Optional<Accepted> verify(String payload, String purpose);

    /**
     * A verified solution.
     *
     * @param nonce     the challenge's random identifier, the key of the one-time reserve
     * @param expiresAt when the challenge expires
     */
    record Accepted(String nonce, Instant expiresAt) {
    }
}
