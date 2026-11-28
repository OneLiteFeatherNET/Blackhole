package net.onelitefeather.blackhole.backend.publicapi.challenge;

import jakarta.inject.Singleton;
import org.altcha.altcha.v2.Altcha;

import java.time.Instant;
import java.util.Optional;

/**
 * The adapter around the ALTCHA library (protocol v2, {@code PBKDF2/SHA-256}). The library checks the signature
 * before it runs the key derivation, so a payload without a valid signature costs no derivation. The purpose is
 * read from the signed data after the check. Any failure to parse or verify is an empty answer, never an exception.
 */
@Singleton
public class AltchaChallengeVerifier implements ChallengeVerifier {

    /** The HMAC of the challenge signature; the same value the issuer uses. */
    static final String HMAC_ALGORITHM = "SHA-256";

    private final ChallengeKeyGuard keys;

    public AltchaChallengeVerifier(ChallengeKeyGuard keys) {
        this.keys = keys;
    }

    @Override
    public Optional<Accepted> verify(String payload, String purpose) {
        try {
            Altcha.Payload parsed = Altcha.parsePayload(payload);
            Altcha.VerifySolutionResult result = Altcha.verifySolution(parsed.challenge(), parsed.solution(), this.keys.key(), null,
                    HMAC_ALGORITHM, Altcha.pbkdf2());
            if (!result.verified()) {
                return Optional.empty();
            }
            Altcha.ChallengeParameters parameters = parsed.challenge().parameters();
            if (parameters.data() == null || !purpose.equals(parameters.data().get("purpose"))) {
                return Optional.empty();
            }
            if (parameters.expiresAt() == null) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.ofEpochMilli(Math.round(parameters.expiresAt().doubleValue() * 1000));
            return Optional.of(new Accepted(parameters.nonce(), expiresAt));
        } catch (Exception e) {
            // The payload is the player's input: a parse or verification error is simply a refused solution.
            return Optional.empty();
        }
    }
}
