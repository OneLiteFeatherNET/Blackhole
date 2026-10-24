package net.onelitefeather.blackhole.discord.gateway;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the bot needs from Discord, behind an interface so everything above it is tested with a fake and
 * JDA types stay inside {@link JdaGateway}. Ids are Discord snowflakes as {@code long}.
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

    /** The permissions the bot sets or checks on a channel. */
    enum Permission {
        VIEW, SEND, READ_HISTORY, EMBED_LINKS, ATTACH_FILES, MANAGE_CHANNEL, MANAGE_PERMISSIONS, MANAGE_MESSAGES
    }

    /** A per-member permission overwrite of a channel. */
    record MemberAccess(long userId, @NotNull Set<Permission> allow, @NotNull Set<Permission> deny) {
    }

    /**
     * A new private ticket channel. {@code @everyone} is denied View; there is no role overwrite.
     *
     * @param name       the channel name
     * @param topic      the topic; it carries the ticket marker
     * @param categoryId the category to create it in
     * @param members    member overwrites (the player, the bot, the linked staff)
     */
    record NewChannel(@NotNull String name, @NotNull String topic, long categoryId, @NotNull List<MemberAccess> members) {
    }

    /**
     * A file or sticker of a message. The {@code url} is the Discord CDN link the bot may download from; it is never stored or
     * logged, and a sticker has none.
     */
    record IncomingAttachment(@NotNull String kind, String id, String name, String contentType, Long size, Integer width, Integer height,
                              String url) {

        public IncomingAttachment(@NotNull String kind, String id, String name, String contentType, Long size, Integer width, Integer height) {
            this(kind, id, name, contentType, size, width, height, null);
        }
    }

    /** An embed of a message: the type and host only. */
    record IncomingEmbed(@NotNull String type, String host) {
    }

    /**
     * A message in a guild channel as the core sees it.
     *
     * @param messageId    the message
     * @param channelId    its channel (for a thread, the thread)
     * @param categoryId   the category of the channel (of the parent for a thread), 0 for none
     * @param publicChannel whether {@code @everyone} can view the channel (the parent for a thread)
     * @param authorId     the author
     * @param authorName   the author's display name
     * @param bot          whether the author is a bot
     * @param self         whether the author is this bot
     * @param hasStaffRole whether the author holds the staff role
     * @param createdAt    when it was written
     * @param editedAt     when it was last edited, or null
     * @param text         the display text, mentions resolved to names
     * @param attachments  file and sticker metadata
     * @param embeds       embed type and host
     * @param relayRef     for the bot's own relays: the reference in the footer, else null
     */
    record IncomingMessage(
            long messageId, long channelId, long categoryId, boolean publicChannel, long authorId, @Nullable String authorName, boolean bot, boolean self,
            boolean hasStaffRole, @NotNull Instant createdAt, @Nullable Instant editedAt, @NotNull String text,
            @NotNull List<IncomingAttachment> attachments, @NotNull List<IncomingEmbed> embeds, @Nullable String relayRef
    ) {
    }

    /** What happens in Discord that the bot reacts to. Methods are called from the gateway's own threads; implementations hand off. */
    interface Listener {

        /** The gateway is connected and ready (also after a reconnect). */
        void onReady();

        void onMessage(@NotNull IncomingMessage message);

        void onMessageEdit(@NotNull IncomingMessage message);

        void onMessageDelete(long channelId, long messageId);

        void onChannelDelete(long channelId);
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

    /** Registers the one listener for Discord events; call before {@link #start()}. */
    void listener(@NotNull Listener listener);

    /** @return the bot's own user id; valid after {@link #start()} */
    long selfId();

    /** Makes sure the "open an appeal" panel message with its button exists in the channel; finds an existing one by its footer marker. */
    void ensureAppealPanel(long channelId, @NotNull String title, @NotNull String body, @NotNull String buttonLabel, @NotNull String marker) throws GatewayException;

    // ---- channels ----

    /** @return the new channel's id */
    long createChannel(@NotNull NewChannel channel) throws GatewayException;

    /** @return the channel in one of the categories whose topic contains the marker, if any */
    @NotNull Optional<Long> findChannelByTopic(@NotNull List<Long> categoryIds, @NotNull String marker) throws GatewayException;

    /** @return whether the channel still exists and the bot can see it */
    boolean channelExists(long channelId);

    void moveChannel(long channelId, long categoryId) throws GatewayException;

    void deleteChannel(long channelId) throws GatewayException;

    /** Sets (replaces) the member's overwrite on the channel. */
    void setMemberAccess(long channelId, @NotNull MemberAccess access) throws GatewayException;

    /** Removes the member's overwrite. */
    void removeMemberAccess(long channelId, long userId) throws GatewayException;

    /** @return the ids of members that have an overwrite on the channel, without the bot itself */
    @NotNull Set<Long> memberAccess(long channelId) throws GatewayException;

    /** @return whether the user is a guild member holding the role (a REST lookup; no member cache) */
    boolean hasRole(long userId, long roleId) throws GatewayException;

    // ---- messages ----

    /** Posts a relayed message: a labelled embed whose footer carries {@code ref}. */
    @NotNull MessageRef postRelay(long channelId, @NotNull String label, @NotNull String text, @NotNull String ref) throws GatewayException;

    /** Replaces the text of a relayed message, keeping its label and reference. A message that is already gone is not an error. */
    void editRelay(@NotNull MessageRef message, @NotNull String text) throws GatewayException;

    /** Deletes a message; one that is already gone is not an error. */
    void deleteMessage(@NotNull MessageRef message) throws GatewayException;

    /** Posts a plain text notice (no mentions of users or roles). */
    void notice(long channelId, @NotNull String text) throws GatewayException;

    /** Posts plain text (no mentions of anything) and returns where it landed. */
    @NotNull MessageRef postPlain(long channelId, @NotNull String text) throws GatewayException;

    /**
     * Uploads a file to the channel with an optional caption (no mentions of anything).
     *
     * @param text the caption, or null for none
     */
    @NotNull MessageRef postFile(long channelId, @Nullable String text, @NotNull String fileName, byte @NotNull [] data) throws GatewayException;

    /** Adds a reaction (a Unicode emoji) to a message; one that is already gone is not an error. */
    void react(@NotNull MessageRef message, @NotNull String emoji) throws GatewayException;

    /**
     * Reads messages after {@code afterId} (0 for the very first) in ascending order, at most {@code limit}. Includes the bot's own
     * messages (flagged), excludes system messages.
     */
    @NotNull List<IncomingMessage> history(long channelId, long afterId, int limit) throws GatewayException;

    /** @return when the channel last saw a message, empty if it does not exist or is empty */
    @NotNull Optional<Instant> lastActivity(long channelId) throws GatewayException;

    // ---- self check ----

    /** @return the permissions the bot lacks in the category (View, Send, Read History, Embed Links, Manage Channels, Manage Permissions) */
    @NotNull Set<Permission> missingPermissions(long categoryId);

    /** @return whether the role can see the category (the staff role must not: access is per linked member) */
    boolean roleCanView(long categoryId, long roleId);

    @Override
    void close();
}
