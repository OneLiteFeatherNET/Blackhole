package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;
import java.util.UUID;

@Serdeable
@ReflectiveAccess
public record EloProfileDTO(
        @NonNull UUID tenantId,
        @NonNull String owner,
        int chatElo,
        int gameplayElo,
        long chatEloUpdatedAt,
        long gameplayEloUpdatedAt,
        @NonNull Map<String, Object> metaData
) {
}
