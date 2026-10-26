package net.onelitefeather.blackhole.backend.kick.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.kick.KickKind;
import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.kick.KickStatus;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code /kick}. The staff member behind a kick is the caller of the verified token;
 * no request carries an actor.
 */
@Schema(description = "Kicking players from the network, a server or a section")
@Serdeable
public sealed interface KickDTO {

    /**
     * Kick one player. The {@code Idempotency-Key} header (not a field) makes a repeat answer the same kick.
     *
     * @param owner  the hashed player
     * @param scope  where to take the player from; {@code NETWORK} when absent
     * @param target the server name or section key for {@code SERVER} and {@code SECTION}, absent for {@code NETWORK}
     * @param reason why, 1 to 200 characters, shown to the player; no UUID, IP address or Ban ID
     */
    @Schema(name = "KickCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull String owner,
            @Nullable KickScope scope,
            @Nullable String target,
            @NonNull String reason
    ) implements KickDTO {
    }

    /**
     * Kick everybody from a server or section. One audited action; no punishment, case or ELO is written.
     *
     * @param scope  {@code SERVER} or {@code SECTION}
     * @param target the server name or section key
     * @param reason why, 1 to 200 characters; no UUID, IP address or Ban ID
     */
    @Schema(name = "KickMassCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record MassCreateRequest(
            @NonNull KickScope scope,
            @NonNull String target,
            @NonNull String reason
    ) implements KickDTO {
    }

    /**
     * The kick was written and the proxies were told. Nothing is delivered yet: poll {@code GET /kick/{id}}.
     *
     * @param id         the kick's identifier, derived from the {@code Idempotency-Key}
     * @param kind       {@code PLAYER} or {@code MASS}
     * @param status     {@code PENDING} for a new kick; the current status for a repeat
     * @param deadlineAt when the status stops being {@code PENDING}, epoch milliseconds
     * @param warnings   things staff should know, such as a ban the kick does not change
     * @param repeated   whether the {@code Idempotency-Key} was already used: nothing was written
     */
    @Schema(name = "KickAccepted")
    @Serdeable
    @ReflectiveAccess
    record Accepted(
            @NonNull String id,
            @NonNull KickKind kind,
            @NonNull KickStatus status,
            long deadlineAt,
            @NonNull List<String> warnings,
            boolean repeated
    ) implements KickDTO {
    }

    /**
     * A kick and how it went so far.
     *
     * @param owner          the hashed player; absent for a mass kick
     * @param target         the server or section; absent for a network kick
     * @param actor          the display name of the staff member, if known
     * @param deliveredCount how many proxies delivered the kick
     * @param deadlineAt     when the status stops being {@code PENDING}, epoch milliseconds
     */
    @Schema(name = "KickResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull String id,
            @NonNull KickKind kind,
            @Nullable String owner,
            @NonNull KickScope scope,
            @Nullable String target,
            @NonNull String reason,
            @Nullable String actor,
            long createdAt,
            @NonNull KickStatus status,
            int deliveredCount,
            long deadlineAt
    ) implements KickDTO {
    }

    /**
     * What one proxy did about a kick.
     *
     * @param proxyId the configured name of the proxy
     * @param found   whether the player was connected to that proxy (a mass kick: whether anybody was on the target)
     * @param moved   whether the player was moved or disconnected (a mass kick: whether at least one player was)
     */
    @Schema(name = "KickAck")
    @Serdeable
    @ReflectiveAccess
    record Ack(
            @NonNull String proxyId,
            boolean found,
            boolean moved
    ) implements KickDTO {
    }

    @Schema(name = "KickError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements KickDTO, ErrorResponse {
    }
}
