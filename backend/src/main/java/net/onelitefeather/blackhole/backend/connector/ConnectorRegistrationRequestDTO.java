package net.onelitefeather.blackhole.backend.connector;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Serdeable
@ReflectiveAccess
public record ConnectorRegistrationRequestDTO(
        @NonNull @NotBlank String name,
        @NonNull @NotEmpty List<String> scopes
) {
}
