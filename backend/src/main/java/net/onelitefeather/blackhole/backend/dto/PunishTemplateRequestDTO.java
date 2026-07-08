package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

/**
 * Request body for creating/updating a punishment template. {@code identifier} is {@code null}
 * for creation, set for update - the same nullable-identifier-means-create convention used
 * elsewhere. The tenant is taken from the URL, not this body.
 */
@Serdeable
@ReflectiveAccess
public record PunishTemplateRequestDTO(
        @NonNull @NotBlank Map<String, Object> metaData,
        @NonNull @NotBlank String reason,
        @NonNull @NotBlank PunishType type,
        int eloDelta,
        @Nullable UUID identifier
) {
}
