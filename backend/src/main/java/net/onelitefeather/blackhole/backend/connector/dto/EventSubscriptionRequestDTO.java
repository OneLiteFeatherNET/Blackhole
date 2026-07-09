package net.onelitefeather.blackhole.backend.connector.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Serdeable
@ReflectiveAccess
public record EventSubscriptionRequestDTO(
        @NonNull UUID connectorId,
        @NonNull @NotEmpty List<String> eventTypes,
        @NonNull @NotBlank String deliveryUrl
) {
}
