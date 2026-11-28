package net.onelitefeather.blackhole.backend.publicapi.challenge;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicChallengeDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import org.altcha.altcha.v2.Altcha;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Issues a challenge for one purpose. Stateless: it needs no store, so it keeps working while Dragonfly is down.
 * The expiry is taken from the injected clock, not from the library, which would read the system time.
 */
@Singleton
public class ChallengeIssuer {

    /** The purposes a challenge can be issued for; each route checks its own. */
    static final List<String> PURPOSES = List.of("sign-in", "appeal");

    private static final String ALGORITHM = "PBKDF2/SHA-256";
    private static final int KEY_LENGTH = 32;

    private final ChallengeKeyGuard keys;
    private final PublicChallengeProperties properties;
    private final Clock clock;
    private final ChallengeMetrics metrics;

    public ChallengeIssuer(ChallengeKeyGuard keys, PublicChallengeProperties properties, Clock clock, ChallengeMetrics metrics) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @return the signed challenge for the purpose, {@link PublicChallengeDTO.Disabled} when the check is off, or an
     *         {@code UNKNOWN_PURPOSE} error for a purpose that no route uses
     */
    public PublicChallengeDTO issue(String purpose) {
        if (!this.properties.enabled()) {
            return new PublicChallengeDTO.Disabled(false);
        }
        if (purpose == null || !PURPOSES.contains(purpose)) {
            return new PublicChallengeDTO.Error("UNKNOWN_PURPOSE", null, PublicErrorKind.VALIDATION);
        }
        long expiresAt = this.clock.instant().plus(this.properties.expiry()).getEpochSecond();
        try {
            Altcha.Challenge challenge = Altcha.createChallenge(new Altcha.CreateChallengeOptions()
                    .algorithm(ALGORITHM)
                    .cost(this.properties.cost())
                    .keyLength(KEY_LENGTH)
                    .keyPrefix(this.properties.keyPrefix())
                    .expiresAt(expiresAt)
                    .data(Map.<String, Object>of("purpose", purpose))
                    .hmacAlgorithm(AltchaChallengeVerifier.HMAC_ALGORITHM)
                    .hmacSignatureSecret(this.keys.key()));
            Altcha.ChallengeParameters p = challenge.parameters();
            this.metrics.issued();
            return new PublicChallengeDTO.Issued(
                    new PublicChallengeDTO.Parameters(p.algorithm(), p.nonce(), p.salt(), p.cost(), p.keyLength(), p.keyPrefix(),
                            p.expiresAt().longValue(), new PublicChallengeDTO.Data(purpose)),
                    challenge.signature());
        } catch (Exception e) {
            throw new IllegalStateException("the challenge could not be signed with the configured key", e);
        }
    }
}
