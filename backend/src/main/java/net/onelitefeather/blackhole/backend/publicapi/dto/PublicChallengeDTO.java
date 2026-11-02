package net.onelitefeather.blackhole.backend.publicapi.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire contract of {@code GET /public/challenge}. The {@link Issued} body is the challenge the widget takes as it is:
 * the widget sends the same parameters back, and the signature covers them.
 */
@Schema(description = "The proof-of-work challenge of sign-in and appeal filing")
@Serdeable
public sealed interface PublicChallengeDTO {

    /**
     * A challenge to solve. The solution goes back in the {@code altcha} field of the next request.
     *
     * @param parameters the challenge parameters, the purpose included in {@code data}
     * @param signature  the HMAC over the canonical parameters
     */
    @Schema(name = "PublicChallenge")
    @Serdeable
    @ReflectiveAccess
    record Issued(@NonNull Parameters parameters, @NonNull String signature) implements PublicChallengeDTO {
    }

    /**
     * The parameters of a challenge.
     *
     * @param algorithm the key derivation, {@code PBKDF2/SHA-256}
     * @param nonce     the random identifier, used once
     * @param salt      the random salt of the derivation
     * @param cost      the PBKDF2 iterations per try
     * @param keyLength the derived key length in bytes
     * @param keyPrefix the hex prefix the derived key must start with
     * @param expiresAt the expiry, Unix seconds
     * @param data      the purpose the challenge was issued for
     */
    @Schema(name = "PublicChallengeParameters")
    @Serdeable
    @ReflectiveAccess
    record Parameters(
            @NonNull String algorithm,
            @NonNull String nonce,
            @NonNull String salt,
            int cost,
            int keyLength,
            @NonNull String keyPrefix,
            long expiresAt,
            @NonNull Data data
    ) {
    }

    /**
     * The signed data of a challenge.
     *
     * @param purpose {@code sign-in} or {@code appeal}; a solution opens only the route of its purpose
     */
    @Schema(name = "PublicChallengeData")
    @Serdeable
    @ReflectiveAccess
    record Data(@NonNull String purpose) {
    }

    /** The check is off (dev and test only): the widget is not shown and no {@code altcha} field is sent. */
    @Schema(name = "PublicChallengeDisabled")
    @Serdeable
    @ReflectiveAccess
    record Disabled(boolean enabled) implements PublicChallengeDTO {
    }

    /**
     * The purpose is missing or not one of the two; a request can never make the challenge answer anything else.
     */
    @Schema(name = "PublicChallengeError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String code, @Nullable String availableFrom, @NonNull PublicErrorKind kind)
            implements PublicChallengeDTO, PublicError {
    }
}
