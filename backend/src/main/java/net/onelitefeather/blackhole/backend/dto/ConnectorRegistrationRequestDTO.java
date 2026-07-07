package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Serdeable
@ReflectiveAccess
public record ConnectorRegistrationRequestDTO(
        @NonNull UUID tenantId,
        @NonNull @NotBlank String name,
        @NonNull @NotEmpty List<String> scopes
) {
}
