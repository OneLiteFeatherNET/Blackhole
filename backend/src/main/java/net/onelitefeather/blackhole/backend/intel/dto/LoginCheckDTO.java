package net.onelitefeather.blackhole.backend.intel.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.intel.IntelClass;
import net.onelitefeather.blackhole.backend.intel.IntelPolicy;
import net.onelitefeather.blackhole.backend.intel.LoginDenialReason;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/**
 * Wire contract of {@code POST /intel/login-check}, the proxy's call after its ban check. Proxy token only.
 */
@Schema(description = "The VPN and hosting verdict for one login")
@Serdeable
public sealed interface LoginCheckDTO {

    /**
     * @param owner the player's owner hash, 128 hex characters
     * @param ip    the client's IPv4 or IPv6 address, as written
     */
    @Schema(name = "LoginCheckRequest")
    @Serdeable
    @ReflectiveAccess
    record CheckRequest(@NonNull String owner, @NonNull String ip) implements LoginCheckDTO {
    }

    /**
     * @param verdict        ALLOW, FLAG or DENY; the proxy refuses the login only on DENY
     * @param classification the class that decided the verdict, or null for a clean address
     * @param sourceKey      the source that matched that class, or null
     * @param denialReason   why a DENY was given, or null for ALLOW and FLAG
     * @param expiresAt      when an area ban denial ends, epoch milliseconds; null for any other answer and for a permanent ban
     */
    @Schema(name = "LoginCheckResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull IntelPolicy verdict, @Nullable IntelClass classification, @Nullable String sourceKey,
                    @Nullable LoginDenialReason denialReason, @Nullable Long expiresAt) implements LoginCheckDTO {

        /** An answer without a denial reason: ALLOW or FLAG, or a VPN policy DENY. */
        public Response(IntelPolicy verdict, IntelClass classification, String sourceKey) {
            this(verdict, classification, sourceKey, null, null);
        }

        /** An answer with a denial reason and no expiry. */
        public Response(IntelPolicy verdict, IntelClass classification, String sourceKey, LoginDenialReason denialReason) {
            this(verdict, classification, sourceKey, denialReason, null);
        }
    }

    @Schema(name = "LoginCheckError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements LoginCheckDTO, ErrorResponse {
    }
}
