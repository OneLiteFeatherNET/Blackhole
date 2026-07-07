package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;
import java.util.UUID;

@Serdeable
@ReflectiveAccess
public record EloEventDTO(
        @NonNull UUID identifier,
        @NonNull UUID tenantId,
        @NonNull String owner,
        @NonNull EloTrack track,
        int delta,
        @NonNull EloReasonCode reasonCode,
        @Nullable UUID sourceEvidenceId,
        int resultingScore,
        long createdAt,
        @NonNull Map<String, Object> metaData
) {
}
