package net.onelitefeather.blackhole.discord.gateway;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the bot needs from Discord, behind an interface so everything above it is tested with a fake and
 * JDA types stay inside {@link JdaGateway}. Later phases (tickets) add their calls here.
 */
public interface DiscordGateway extends AutoCloseable {

    /** A message the bot posted: where it is, so it can be edited. */
    record MessageRef(long channelId, long messageId) {
    }

    /** One field of an embed. */
    record Field(@NotNull String name, @NotNull String value, boolean inline) {
    }

    /**
     * A message with one embed and an optional link button, in the bot's terms.
     *
     * @param content   text above the embed (the role ping), or null for none
     * @param title     the embed title
     * @param color     the embed colour as RGB
     * @param fields    the embed fields
     * @param footer    the embed footer; it carries the marker by which the bot finds its message again
     * @param timestamp the embed timestamp
     * @param linkLabel the label of the link button, or null for no button
     * @param linkUrl   where the button leads
     */
    record MessageSpec(
            @Nullable String content, @NotNull String title, int color, @NotNull List<Field> fields,
            @NotNull String footer, @NotNull Instant timestamp, @Nullable String linkLabel, @Nullable String linkUrl
    ) {
    }

    /** Discord refused or could not be reached. {@code permanent} means retrying cannot help (no access, unknown channel). */
    final class GatewayException extends Exception {

        private final boolean permanent;

        public GatewayException(@NotNull String message, boolean permanent, @Nullable Throwable cause) {
            super(message, cause);
            this.permanent = permanent;
        }

        public boolean permanent() {
            return this.permanent;
        }
    }

    /**
     * Connects to Discord and registers the slash commands in the guild.
     *
     * @throws InterruptedException if interrupted while waiting for the connection
     * @throws IllegalStateException if the bot is not a member of the configured guild
     */
    void start() throws InterruptedException;

    /** @return whether the gateway connection is up */
    boolean connected();

    /** Posts a message to a channel. */
    @NotNull MessageRef post(long channelId, @NotNull MessageSpec spec) throws GatewayException;

    /** Replaces a message the bot posted. */
    void edit(@NotNull MessageRef message, @NotNull MessageSpec spec) throws GatewayException;

    /**
     * Looks through the recent messages of a channel for one the bot posted whose embed footer ends with
     * the marker, so the bot stays stateless across restarts.
     */
    @NotNull Optional<MessageRef> find(long channelId, @NotNull String marker) throws GatewayException;

    @Override
    void close();
}
