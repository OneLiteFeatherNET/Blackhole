package net.onelitefeather.blackhole.backend.proxy.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.time.Instant;
import java.util.List;

/**
 * Wire contract of {@code /proxy/capabilities}: whether each proxy has SignedVelocity loaded. Without it a
 * proxy cannot cancel a muted player's signed chat, so the admin panel warns while any proxy lacks it.
 */
@Schema(description = "Whether each proxy has SignedVelocity loaded, for the admin panel's warning")
@Serdeable
public sealed interface ProxyCapabilityDTO {

    @Schema(name = "ProxyCapabilitySignedVelocity", description = "The relay state a proxy reports")
    @Serdeable
    @ReflectiveAccess
    record SignedVelocity(boolean present, @Nullable String version) {
    }

    @Schema(name = "ProxyCapabilityReportRequest", description = "A proxy's full state, replacing its previous report")
    @Serdeable
    @ReflectiveAccess
    record Report(@NonNull @NotBlank String proxyId, @NotNull @Valid SignedVelocity signedVelocity) implements ProxyCapabilityDTO {
    }

    @Schema(name = "ProxyCapabilityStored")
    @Serdeable
    @ReflectiveAccess
    record Stored(@NonNull String proxyId, @NonNull Instant reportedAt) implements ProxyCapabilityDTO {
    }

    @Schema(name = "ProxyRelayState", description = "PRESENT, ABSENT, or UNKNOWN when the last report is older than the stale window")
    @Serdeable
    enum RelayState {
        PRESENT, ABSENT, UNKNOWN
    }

    @Schema(name = "ProxyRelayEntry")
    @Serdeable
    @ReflectiveAccess
    record Relay(@NonNull String proxyId, @NonNull RelayState state, @Nullable String version, @Nullable Instant reportedAt) {
    }

    @Schema(name = "ProxyCapabilityResponse", description = "The last state of every proxy that has reported")
    @Serdeable
    @ReflectiveAccess
    record Response(@NonNull List<Relay> proxies) implements ProxyCapabilityDTO {
    }

    @Schema(name = "ProxyCapabilityError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ProxyCapabilityDTO, ErrorResponse {
    }
}
