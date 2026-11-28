package net.onelitefeather.blackhole.backend.intel.names.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * Wire contract of the name history under {@code /intel}. Names carry the raw player UUID, so only staff and the
 * proxy's staff command read it; nothing public carries it.
 */
@Schema(description = "The names a player account used, from its login sessions")
@Serdeable
public sealed interface NameHistoryDTO {

    /**
     * One page of the names of one owner, newest last seen first.
     *
     * @param owner      the hashed player, 128 hex characters
     * @param playerUuid the raw UUID of the account; null when the page holds no names
     * @param names      the names on this page
     * @param page       the zero-based page
     * @param size       the page size
     * @param hasMore    whether a next page exists
     */
    @Schema(name = "NameHistoryPage")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull String owner,
            @Nullable String playerUuid,
            @JsonInclude(JsonInclude.Include.ALWAYS) @NonNull List<NameEntry> names,
            int page,
            int size,
            boolean hasMore
    ) implements NameHistoryDTO {
    }

    /**
     * The owners that used one name, newest last seen first; at most 20.
     *
     * @param owners the owners that matched
     */
    @Schema(name = "NameOwnersResponse")
    @Serdeable
    @ReflectiveAccess
    record OwnerResponse(@JsonInclude(JsonInclude.Include.ALWAYS) @NonNull List<OwnerEntry> owners) implements NameHistoryDTO {
    }

    @Schema(name = "NameHistoryError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements NameHistoryDTO, ErrorResponse {
    }

    /**
     * One name of an owner.
     *
     * @param name      the newest casing of the name
     * @param firstSeen the first login with the name, epoch milliseconds
     * @param lastSeen  the last login with the name, epoch milliseconds
     */
    @Schema(name = "NameHistoryName")
    @Serdeable
    @ReflectiveAccess
    record NameEntry(@NonNull String name, long firstSeen, long lastSeen) {
    }

    /**
     * An owner that used the looked-up name.
     *
     * @param owner       the hashed player
     * @param playerUuid  the raw UUID of the account
     * @param currentName the owner's newest name, which may differ from the looked-up one
     * @param firstSeen   the first login with the looked-up name, epoch milliseconds
     * @param lastSeen    the last login with the looked-up name, epoch milliseconds
     */
    @Schema(name = "NameHistoryOwner")
    @Serdeable
    @ReflectiveAccess
    record OwnerEntry(@NonNull String owner, @NonNull String playerUuid, @NonNull String currentName, long firstSeen, long lastSeen) {
    }
}
