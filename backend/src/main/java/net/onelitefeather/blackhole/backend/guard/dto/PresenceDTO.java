package net.onelitefeather.blackhole.backend.guard.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/** Wire contract of the staff presence heartbeat that each proxy sends every 30 seconds. Proxy token only. */
@Schema(description = "The staff presence one proxy reports")
@Serdeable
public sealed interface PresenceDTO {

    /**
     * @param proxyId    the proxy's configured id
     * @param staffOnline the number of online players that hold the staff permission node
     */
    @Schema(name = "GuardPresenceRequest")
    @Serdeable
    @ReflectiveAccess
    record Heartbeat(@NonNull String proxyId, int staffOnline) implements PresenceDTO {
    }

    /** @param staffPresent whether any fresh proxy reports staff online after this heartbeat */
    @Schema(name = "GuardPresenceResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(boolean staffPresent) implements PresenceDTO {
    }

    @Schema(name = "GuardPresenceError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements PresenceDTO, ErrorResponse {
    }
}
