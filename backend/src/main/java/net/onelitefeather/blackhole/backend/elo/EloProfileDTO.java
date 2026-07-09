package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Serdeable
@ReflectiveAccess
public record EloProfileDTO(
        @NonNull String owner,
        int chatElo,
        int gameplayElo,
        long chatEloUpdatedAt,
        long gameplayEloUpdatedAt,
        @NonNull Map<String, Object> metaData
) {
}
