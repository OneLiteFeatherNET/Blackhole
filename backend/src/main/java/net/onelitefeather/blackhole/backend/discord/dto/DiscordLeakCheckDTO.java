package net.onelitefeather.blackhole.backend.discord.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;

/**
 * The Ban ID leak check of the Discord bot. The bot posts what looks like a Ban ID in a public channel; only the backend can tell whether it
 * is one. The answer never names the punishment, the Ban ID or the poster, and a candidate that is no Ban ID leaves no trace.
 */
@Schema(description = "Verifies Ban ID look-alikes found in a public Discord channel and marks real ones leaked")
@Serdeable
public sealed interface DiscordLeakCheckDTO {

    /** Where the message was found. A ticket channel is never scanned, so it is not an option. */
    @Schema(name = "DiscordLeakChannelKind")
    @Serdeable
    enum ChannelKind {
        /** A channel where {@code @everyone} can view. */
        PUBLIC,
        /** A channel the operator listed ({@code DISCORD_LEAK_SCAN=LIST}). */
        LISTED
    }

    /**
     * @param candidates           up to five normalised candidates (8 Crockford characters; the backend normalises again)
     * @param channelKind          where the message was posted
     * @param posterDiscordUserId  who posted it; stored only as the opaque participant of the leaked case
     * @param posterIsBot          whether Discord marks the poster a bot
     */
    @Schema(name = "DiscordLeakCheckRequest")
    @Serdeable
    @ReflectiveAccess
    record Request(
            @NonNull List<String> candidates, @NonNull ChannelKind channelKind, @Nullable String posterDiscordUserId, boolean posterIsBot
    ) implements DiscordLeakCheckDTO {
    }

    /**
     * @param matched how many candidates are a current Ban ID (the bot deletes the message when this is above zero)
     * @param marked  how many of them were marked leaked now (zero for one that was marked before)
     */
    @Schema(name = "DiscordLeakCheckResult")
    @Serdeable
    @ReflectiveAccess
    record Result(int matched, int marked) implements DiscordLeakCheckDTO {
    }

    @Schema(name = "DiscordLeakCheckError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements DiscordLeakCheckDTO, ErrorResponse {
    }
}
