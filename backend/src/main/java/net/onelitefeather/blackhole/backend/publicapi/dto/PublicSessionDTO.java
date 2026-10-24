package net.onelitefeather.blackhole.backend.publicapi.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;

/**
 * Wire contract of the player session routes. A login that does not succeed is always the same
 * {@link Error}, whatever the reason.
 */
@Schema(description = "The player session of the public API")
@Serdeable
public sealed interface PublicSessionDTO {

    /**
     * Logs in. The Ban ID travels in this body and nowhere else.
     *
     * @param banId the eight characters, as typed
     */
    @Schema(name = "PublicLoginRequest")
    @Serdeable
    @ReflectiveAccess
    record LoginRequest(@Nullable String banId) implements PublicSessionDTO {

        /** Never prints the Ban ID. */
        @Override
        public String toString() {
            return "LoginRequest[banId=***]";
        }
    }

    /**
     * A good login: the token is shown once, the portal keeps it in a cookie.
     *
     * @param csrf      sent back as {@code X-CSRF-Token} on every state-changing request
     * @param expiresAt the absolute end of the session, an ISO instant
     */
    @Schema(name = "PublicLogin")
    @Serdeable
    @ReflectiveAccess
    record Created(@NonNull String token, @NonNull String csrf, @NonNull String expiresAt, @NonNull PlayerScope scope) implements PublicSessionDTO {

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Created[token=***, scope=" + this.scope + "]";
        }
    }

    /** The session behind the request, for the portal's bootstrap. */
    @Schema(name = "PublicSession")
    @Serdeable
    @ReflectiveAccess
    record Current(@NonNull String csrf, @NonNull String expiresAt, @NonNull PlayerScope scope) implements PublicSessionDTO {
    }

    @Schema(name = "PublicSessionError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String code, @Nullable String availableFrom, @NonNull PublicErrorKind kind) implements PublicSessionDTO, PublicError {
    }
}
