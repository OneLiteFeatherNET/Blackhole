package net.onelitefeather.blackhole.backend.discord.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.UUID;

/**
 * What the Discord bot needs to render a team notification for a report or an appeal. Domain events
 * carry only hashed owners, so the bot reads the display facts here. Nothing in it identifies a player
 * beyond {@code playerName} (when the proxy recorded one) and a short pseudonymous {@code playerRef}:
 * never a raw UUID, never a Ban ID.
 */
@Schema(description = "The facts for a team notification about a report or an appeal")
@Serdeable
public sealed interface DiscordNotificationDTO {

    /**
     * @param identifier  the report
     * @param playerName  the reported player's name, only when the report recorded one
     * @param playerRef   the first 8 characters of the reported player's hash, a pseudonym shown as fallback
     * @param category    what the report is about
     * @param description the reporter's text, shortened
     * @param status      OPEN, UNDER_REVIEW, ACTIONED or DISMISSED
     * @param createdAt   epoch millis
     * @param updatedAt   epoch millis
     */
    @Schema(name = "DiscordReportNotification")
    @Serdeable
    @ReflectiveAccess
    record Report(
            @NonNull UUID identifier, @Nullable String playerName, @NonNull String playerRef, @NonNull String category,
            @Nullable String description, @NonNull String status, long createdAt, long updatedAt
    ) implements DiscordNotificationDTO {
    }

    /**
     * @param identifier           the appeal
     * @param punishmentIdentifier the appealed punishment, for the case link in the panel
     * @param playerName           the appellant's name, only when it was recorded
     * @param playerRef            the first 8 characters of the appellant's hash
     * @param section              the section of the punishment
     * @param statement            the appellant's statement, shortened
     * @param status               the appeal status
     * @param createdAt            epoch millis
     * @param updatedAt            epoch millis
     */
    @Schema(name = "DiscordAppealNotification")
    @Serdeable
    @ReflectiveAccess
    record Appeal(
            @NonNull UUID identifier, @NonNull String punishmentIdentifier, @Nullable String playerName, @NonNull String playerRef,
            @NonNull String section, @Nullable String statement, @NonNull String status, long createdAt, long updatedAt
    ) implements DiscordNotificationDTO {
    }

    @Schema(name = "DiscordNotificationError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements DiscordNotificationDTO, ErrorResponse {
    }
}
