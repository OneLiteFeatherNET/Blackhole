package net.onelitefeather.blackhole.backend.discord.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of the Discord account link: {@code /staff-discord-link}, {@code /player-discord-link},
 * {@code /discord/link} and {@code /admin/discord-link}. A one-time code appears in exactly one
 * response ({@link CodeResponse}), never in an error. A Discord user id is only ever accepted (by the
 * bot's confirmation); no response returns one.
 */
@Schema(description = "A link between a staff member or player and a Discord account")
@Serdeable
public sealed interface DiscordLinkDTO {

    /**
     * What the Velocity proxy sends to get a code for a player.
     *
     * @param owner the player's hashed owner (SHA-512 hex, never the raw UUID)
     */
    @Schema(name = "DiscordLinkPlayerCodeRequest")
    @Serdeable
    @ReflectiveAccess
    record PlayerCodeRequest(@Nullable String owner) implements DiscordLinkDTO {
    }

    /**
     * What the Discord bot sends to confirm a code.
     *
     * @param code          the code the person typed with {@code /link}, with or without dash, in any case
     * @param discordUserId the Discord user (snowflake) who typed it
     */
    @Schema(name = "DiscordLinkConfirmRequest")
    @Serdeable
    @ReflectiveAccess
    record ConfirmRequest(@Nullable String code, @Nullable String discordUserId) implements DiscordLinkDTO {
    }

    /**
     * A freshly issued code.
     *
     * @param code      the code as shown to a person, {@code ABCD-EFGH}; only ever returned here
     * @param expiresAt when it stops working, epoch millis
     */
    @Schema(name = "DiscordLinkCodeResponse")
    @Serdeable
    @ReflectiveAccess
    record CodeResponse(@NonNull String code, long expiresAt) implements DiscordLinkDTO {
    }

    /**
     * The link a confirmation created.
     *
     * @param kind        whose account was linked
     * @param displayName the staff member's name, only for {@link DiscordLinkKind#STAFF} and if known
     * @param linkedAt    when, epoch millis
     */
    @Schema(name = "DiscordLinkConfirmResponse")
    @Serdeable
    @ReflectiveAccess
    record LinkedResponse(
            @NonNull DiscordLinkKind kind, @Nullable String displayName, long linkedAt
    ) implements DiscordLinkDTO {
    }

    /**
     * The caller's own link status.
     *
     * @param linked   whether a Discord account is linked
     * @param linkedAt when it was linked, epoch millis, only when linked
     */
    @Schema(name = "DiscordLinkStatusResponse")
    @Serdeable
    @ReflectiveAccess
    record StatusResponse(boolean linked, @Nullable Long linkedAt) implements DiscordLinkDTO {
    }

    /** One link of a Discord user, as the bot's {@code /whoami} shows it. */
    @Schema(name = "DiscordLinkWhoamiEntry")
    @Serdeable
    @ReflectiveAccess
    record WhoamiEntry(@NonNull DiscordLinkKind kind, @Nullable String displayName, long linkedAt) {
    }

    /**
     * What a Discord user is linked to; empty when nothing is.
     *
     * @param links up to one entry per kind
     */
    @Schema(name = "DiscordLinkWhoamiResponse")
    @Serdeable
    @ReflectiveAccess
    record WhoamiResponse(@NonNull List<WhoamiEntry> links) implements DiscordLinkDTO {
    }

    /**
     * One link in the admin list. The Discord user id is deliberately not part of it.
     *
     * @param id          the link, for removal
     * @param kind        whose account
     * @param subject     the staff {@code oid} or the hashed player owner
     * @param displayName the staff member's name, if known
     * @param linkedAt    when, epoch millis
     */
    @Schema(name = "DiscordLinkItem")
    @Serdeable
    @ReflectiveAccess
    record Item(
            @NonNull UUID id, @NonNull DiscordLinkKind kind, @NonNull String subject,
            @Nullable String displayName, long linkedAt
    ) {
    }

    /** All links, oldest first, for admins. */
    @Schema(name = "DiscordLinkListResponse")
    @Serdeable
    @ReflectiveAccess
    record ListResponse(@NonNull List<Item> items) implements DiscordLinkDTO {
    }

    /**
     * Links that were removed.
     *
     * @param removed how many
     */
    @Schema(name = "DiscordLinkRemovedResponse")
    @Serdeable
    @ReflectiveAccess
    record RemovedResponse(int removed) implements DiscordLinkDTO {
    }

    @Schema(name = "DiscordLinkError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements DiscordLinkDTO, ErrorResponse {
    }
}
