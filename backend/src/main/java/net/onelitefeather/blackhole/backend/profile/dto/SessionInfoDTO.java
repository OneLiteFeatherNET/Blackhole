package net.onelitefeather.blackhole.backend.profile.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Session telemetry captured at login/brand-negotiation time (Phase 7's dashboard enrichment).
 * Both fields are optional and merged, not overwritten, into the profile's existing session
 * info - {@code protocolVersion} arrives at login, {@code clientBrand} arrives slightly later
 * as a separate plugin-channel negotiation, so a single caller rarely has both at once.
 */
@Serdeable
@ReflectiveAccess
public record SessionInfoDTO(
        @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "owner must be a sha-512 hash") String owner,
        @Nullable Integer protocolVersion,
        @Nullable @Size(max = 64) String clientBrand
) {
}
