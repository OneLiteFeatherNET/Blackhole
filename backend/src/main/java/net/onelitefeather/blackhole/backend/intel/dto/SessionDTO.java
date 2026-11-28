package net.onelitefeather.blackhole.backend.intel.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.intel.SessionOutcome;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of {@code /intel}. A session carries the clear IP and the raw player UUID, so only the panel reads
 * it back ({@code GET /intel/players/{owner}/sessions}); the proxy's writes answer with the stored record.
 */
@Schema(description = "A player's login sessions, as reported by the proxies")
@Serdeable
public sealed interface SessionDTO {

    /**
     * One login attempt, reported after the ban check.
     *
     * @param sessionId       the proxy's random UUID for this connection; the repeat key of the report
     * @param owner           the hashed player, 128 hex characters
     * @param playerUuid      the player's raw UUID
     * @param playerName      the name seen at login, 1 to 16 characters
     * @param ip              the client's IPv4 or IPv6 address, as written
     * @param protocolVersion the protocol version the client connected with, when known
     * @param virtualHost     the host the client typed, without the port, when known
     * @param outcome         {@code ALLOWED} or {@code DENIED}
     */
    @Schema(name = "SessionStartRequest")
    @Serdeable
    @ReflectiveAccess
    record StartRequest(
            @NonNull String sessionId,
            @NonNull String owner,
            @NonNull String playerUuid,
            @NonNull String playerName,
            @NonNull String ip,
            @Nullable Integer protocolVersion,
            @Nullable String virtualHost,
            @NonNull SessionOutcome outcome
    ) implements SessionDTO {
    }

    /**
     * The client brand and version, and the disconnect.
     *
     * @param clientBrand   the brand the client announced, at most 64 characters
     * @param clientVersion the client version, at most 64 characters
     * @param ended         true when the player disconnected; the first end report is kept
     */
    @Schema(name = "SessionUpdateRequest")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(
            @Nullable String clientBrand,
            @Nullable String clientVersion,
            boolean ended
    ) implements SessionDTO {
    }

    /**
     * One stored session.
     */
    @Schema(name = "SessionResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull String sessionId,
            @NonNull String owner,
            @NonNull String playerUuid,
            @NonNull String playerName,
            @NonNull String ip,
            @Nullable Long asn,
            @Nullable String asOrg,
            @Nullable String countryCode,
            @Nullable String clientBrand,
            @Nullable String clientVersion,
            @Nullable Integer protocolVersion,
            @Nullable String virtualHost,
            @NonNull SessionOutcome outcome,
            long startedAt,
            @Nullable Long endedAt
    ) implements SessionDTO {
    }

    /**
     * One page of a player's sessions, newest first.
     *
     * @param sessions the sessions on this page
     * @param page     the zero-based page
     * @param size     the page size
     * @param hasMore  whether a next page exists
     */
    @Schema(name = "SessionPage")
    @Serdeable
    @ReflectiveAccess
    record Page(@NonNull List<Response> sessions, int page, int size, boolean hasMore) implements SessionDTO {
    }

    @Schema(name = "SessionError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements SessionDTO, ErrorResponse {
    }
}
