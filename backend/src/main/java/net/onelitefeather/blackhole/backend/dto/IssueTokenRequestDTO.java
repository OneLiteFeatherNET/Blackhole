package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

@Serdeable
@ReflectiveAccess
public record IssueTokenRequestDTO(@NonNull UUID tenantId, @NonNull @NotBlank String role) {
}
