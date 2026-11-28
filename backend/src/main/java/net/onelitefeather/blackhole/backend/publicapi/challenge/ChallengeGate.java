package net.onelitefeather.blackhole.backend.publicapi.challenge;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides whether a request carries a solution that may be used now: the challenge verifies for the purpose, and its
 * nonce is reserved for the first time. Runs before any database read by the caller, so a refused solution never
 * reads a Ban ID or records an attempt. A disabled check (dev and test only) passes everything without touching anything.
 */
@Singleton
public class ChallengeGate {

    /** The error code of every refused solution, the same for all causes. */
    public static final String FAILED_CODE = "CHALLENGE_FAILED";

    /** The error code when the reserve cannot answer. */
    public static final String UNAVAILABLE_CODE = "UNAVAILABLE";

    /** The longest payload accepted; the portal's backend-for-frontend allows the same. */
    static final int MAX_PAYLOAD_CHARACTERS = 8192;

    /** The answer of one check. */
    public enum Verdict {
        /** The solution is good and was used now. */
        PASSED,
        /** The solution is missing, malformed, badly signed, expired, of another purpose or already used. */
        REJECTED,
        /** The reserve did not answer; the request must fail closed. */
        UNAVAILABLE
    }

    private final PublicChallengeProperties properties;
    private final ChallengeVerifier verifier;
    private final ChallengeReplayStore store;
    private final Clock clock;
    private final ChallengeMetrics metrics;

    public ChallengeGate(
            PublicChallengeProperties properties, ChallengeVerifier verifier, ChallengeReplayStore store, Clock clock, ChallengeMetrics metrics
    ) {
        this.properties = properties;
        this.verifier = verifier;
        this.store = store;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @param altcha  the {@code altcha} field of the request, or null when it was not sent
     * @param purpose {@code sign-in} or {@code appeal}, the purpose of the route
     */
    public Verdict check(String altcha, String purpose) {
        if (!this.properties.enabled()) {
            return Verdict.PASSED;
        }
        if (altcha == null || altcha.isBlank() || altcha.length() > MAX_PAYLOAD_CHARACTERS) {
            this.metrics.rejected();
            return Verdict.REJECTED;
        }
        Optional<ChallengeVerifier.Accepted> accepted = this.verifier.verify(altcha, purpose);
        if (accepted.isEmpty()) {
            this.metrics.rejected();
            return Verdict.REJECTED;
        }
        Duration remaining = Duration.between(this.clock.instant(), accepted.get().expiresAt());
        return switch (this.store.reserve(accepted.get().nonce(), remaining)) {
            case RESERVED -> {
                this.metrics.solved();
                yield Verdict.PASSED;
            }
            case REPLAYED -> {
                this.metrics.replayed();
                yield Verdict.REJECTED;
            }
            case UNAVAILABLE -> {
                this.metrics.unavailable();
                yield Verdict.UNAVAILABLE;
            }
        };
    }

    /** The error code for a verdict that is not a pass; the services build their own error variant from it. */
    public static String code(Verdict verdict) {
        return verdict == Verdict.UNAVAILABLE ? UNAVAILABLE_CODE : FAILED_CODE;
    }

    /** The error kind (and so the status) for a verdict that is not a pass: 403 for a refused solution, 503 when unavailable. */
    public static PublicErrorKind kind(Verdict verdict) {
        return verdict == Verdict.UNAVAILABLE ? PublicErrorKind.UNAVAILABLE : PublicErrorKind.CHALLENGE_FAILED;
    }
}
