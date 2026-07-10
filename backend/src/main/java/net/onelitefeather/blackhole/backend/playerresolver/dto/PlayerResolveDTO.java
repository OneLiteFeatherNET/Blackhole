package net.onelitefeather.blackhole.backend.playerresolver.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.playerresolver.ResolvedPlayer;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.UUID;

/**
 * Wire contract for {@code GET /player/resolve/{name}} - a read-only lookup, so unlike a
 * mutable resource's DTO this only needs a success/error pair, no Create/Update request variants.
 */
@Schema(description = "DTO for resolving a player name to a UUID")
@Serdeable
public sealed interface PlayerResolveDTO {

    @Schema(name = "PlayerResolveResponse")
    @Serdeable
    record Response(UUID uuid, String name, String source) implements PlayerResolveDTO {

        public static Response of(ResolvedPlayer resolved) {
            return new Response(resolved.uuid(), resolved.name(), resolved.source());
        }
    }

    @Schema(name = "PlayerResolveError")
    @Serdeable
    record Error(String errorMessage) implements PlayerResolveDTO, ErrorResponse {
    }
}
