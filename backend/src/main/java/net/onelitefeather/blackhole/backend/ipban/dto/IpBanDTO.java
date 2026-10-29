package net.onelitefeather.blackhole.backend.ipban.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of {@code /ipban}. A ban carries the clear address or range, so it is staff only; the proxy's list
 * ({@link ActiveList}) carries no actor and no case.
 */
@Schema(description = "IP bans: one address or range, held at the firewall through its pull feed")
@Serdeable
public sealed interface IpBanDTO {

    /**
     * A new IP ban.
     *
     * @param address     one IPv4 or IPv6 address, or a CIDR range
     * @param reason      the reason, 1 to 200 characters
     * @param templateKey the template the reason comes from, when there is one
     * @param expiresAt   epoch milliseconds, or null for a permanent ban (ADMIN only)
     * @param source      {@code PANEL} (default) or {@code COMMAND}
     */
    @Schema(name = "IpBanCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull String address,
            @NonNull String reason,
            @Nullable String templateKey,
            @Nullable Long expiresAt,
            @Nullable String source
    ) implements IpBanDTO {
    }

    /** The mandatory reason of a lift, 1 to 200 characters. */
    @Schema(name = "IpBanLiftRequest")
    @Serdeable
    @ReflectiveAccess
    record LiftRequest(@NonNull String reason) implements IpBanDTO {
    }

    /**
     * One IP ban as staff read it.
     *
     * @param warnings notes on an accepted ban, such as a narrower ban inside a wider active one
     */
    @Schema(name = "IpBanResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull UUID id,
            @NonNull String range,
            @NonNull String reason,
            @Nullable String templateKey,
            @NonNull String source,
            @Nullable Long expiresAt,
            long createdAt,
            @Nullable UUID actorOid,
            @Nullable Long liftedAt,
            @Nullable UUID liftedByOid,
            @Nullable String liftReason,
            @NonNull List<String> warnings
    ) implements IpBanDTO {
    }

    /** The newest IP bans, active or not. */
    @Schema(name = "IpBanListResponse")
    @Serdeable
    @ReflectiveAccess
    record Listing(@NonNull List<Response> bans) implements IpBanDTO {
    }

    /** One active ban as the proxy enforces it: range, expiry and the reason shown to the player. */
    @Schema(name = "IpBanActiveEntry")
    @Serdeable
    @ReflectiveAccess
    record ActiveEntry(@NonNull UUID id, @NonNull String range, @Nullable Long expiresAt, @NonNull String reason) implements IpBanDTO {
    }

    /** Every active IP ban, for the proxy's in-memory list. */
    @Schema(name = "IpBanActiveList")
    @Serdeable
    @ReflectiveAccess
    record ActiveList(@NonNull List<ActiveEntry> bans) implements IpBanDTO {
    }

    /**
     * An expected failure.
     *
     * @param code the stable code of the rule that failed, such as {@code IPBAN_PROTECTED}
     */
    @Schema(name = "IpBanError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind, @NonNull String code) implements IpBanDTO, ErrorResponse {
    }
}
