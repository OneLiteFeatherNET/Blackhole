package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

/**
 * OAuth2 client-credentials style exchange for a connector-scoped token.
 */
@Serdeable
@ReflectiveAccess
public record ConnectorTokenRequestDTO(
        @NonNull @NotBlank String clientId,
        @NonNull @NotBlank String clientSecret
) {
}
