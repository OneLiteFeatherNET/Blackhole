package net.onelitefeather.blackhole.backend.clientrule.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleAction;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.time.Instant;
import java.util.List;

/**
 * Wire contract of the client rule hits. A hit says that a rule matched a player, never what matched:
 * it carries no brand, channel, mod or command text.
 */
@Schema(description = "A client rule hit: the rule, the hashed player, the time, the action and where it happened. No client trait.")
@Serdeable
public sealed interface ClientRuleHitDTO {

    @Schema(name = "ClientRuleHitCreateRequest", description = "A hit reported by the proxy")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull @NotBlank @Schema(description = "The key of the rule that matched") String rule,
            @NonNull @NotBlank @Schema(description = "The hashed owner (SHA-512 hex)") String owner,
            @Nullable @Schema(description = "The backend server name the player was on or connecting to") String server,
            @Schema(description = "Whether the proxy refused the connection or the command") boolean enforced,
            @Schema(description = "Whether the player holds the bypass permission, so nothing was enforced") boolean bypassed
    ) implements ClientRuleHitDTO {
    }

    @Schema(name = "ClientRuleHitRecorded")
    @Serdeable
    @ReflectiveAccess
    record Recorded(
            @NonNull String rule,
            @NonNull ClientRuleAction action,
            @Schema(description = "Whether a punishment was created from the rule's template by this hit") boolean templateApplied,
            @Nullable @Schema(description = "The created punishment") String punishmentIdentifier
    ) implements ClientRuleHitDTO {
    }

    @Schema(name = "ClientRuleHit")
    @Serdeable
    @ReflectiveAccess
    record View(
            @NonNull String rule,
            @NonNull String owner,
            @NonNull Instant at,
            @NonNull ClientRuleAction action,
            boolean enforced,
            boolean bypassed,
            @Nullable String server
    ) {
    }

    @Schema(name = "ClientRuleHits", description = "The newest hits first")
    @Serdeable
    @ReflectiveAccess
    record Hits(@NonNull List<View> hits) implements ClientRuleHitDTO {
    }

    @Schema(name = "ClientRuleHitError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ClientRuleHitDTO, ErrorResponse {
    }
}
