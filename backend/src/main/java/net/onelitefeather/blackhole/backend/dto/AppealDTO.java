package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;
import java.util.UUID;

@Serdeable
@ReflectiveAccess
public record AppealDTO(
        @NonNull UUID identifier,
        @NonNull String punishmentIdentifier,
        @NonNull String appellantHash,
        @NonNull String statement,
        @NonNull AppealStatus status,
        @NonNull Map<String, Object> eligibilityCheckResult,
        @Nullable UUID decidedBy,
        @Nullable String decisionNote,
        long createdAt,
        long updatedAt,
        @NonNull Map<String, Object> metaData
) {
}
