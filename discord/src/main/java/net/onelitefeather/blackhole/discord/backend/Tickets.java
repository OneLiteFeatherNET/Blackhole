package net.onelitefeather.blackhole.discord.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The ticket facade's data, in the bot's terms (strings for snowflakes, no generated types). */
public final class Tickets {

    private Tickets() {
    }

    /** A ticket's lifecycle state, as the backend keeps it. */
    public enum State {
        PENDING_CHANNEL, OPEN, CLOSING, CLOSED, ARCHIVED, DELETED
    }

    /**
     * @param id                     the ticket
     * @param ticketNo               the number shown in the channel name
     * @param state                  where it is in its life
     * @param appealId               the appeal it belongs to
     * @param channelId              the Discord channel, null while pending
     * @param playerDiscordUserId    the bound player, null once erased
     * @param leaked                 whether the Ban ID of the case is leaked (the channel must be frozen)
     * @param frozen                 whether the bot has frozen the channel (the mirror it reports back)
     * @param ingestedCursor         the highest Discord message id the backend recorded
     * @param relayedSeq             the highest case event relayed into the channel
     * @param transcriptComplete     whether the completeness proof was accepted
     * @param transcriptCount        the proven message count, if complete
     * @param transcriptLastMessageId the proven last message id, if complete
     * @param protocolVersion        the stored protocol version, null while none
     */
    public record Ticket(
            @NotNull String id, long ticketNo, @NotNull State state, @NotNull String appealId, @Nullable String channelId,
            @Nullable String playerDiscordUserId, boolean leaked, boolean frozen, @NotNull String ingestedCursor, long relayedSeq,
            boolean transcriptComplete, @Nullable Integer transcriptCount, @Nullable String transcriptLastMessageId,
            @Nullable Integer protocolVersion
    ) {

        public @Nullable Long channel() {
            return this.channelId == null ? null : Long.parseUnsignedLong(this.channelId);
        }

        public @NotNull Ticket withChannel(long channel) {
            return new Ticket(this.id, this.ticketNo, this.state, this.appealId, Long.toUnsignedString(channel), this.playerDiscordUserId,
                    this.leaked, this.frozen, this.ingestedCursor, this.relayedSeq, this.transcriptComplete, this.transcriptCount,
                    this.transcriptLastMessageId, this.protocolVersion);
        }

        public @Nullable Long player() {
            return this.playerDiscordUserId == null ? null : Long.parseUnsignedLong(this.playerDiscordUserId);
        }
    }

    /**
     * @param ticket      the ticket
     * @param created     false when the player already had this ticket
     * @param appealFiled whether this call filed the appeal
     */
    public record Opened(@NotNull Ticket ticket, boolean created, boolean appealFiled) {
    }

    /** A change the bot reports; a null field is left alone. */
    public record Patch(@Nullable String channelId, @Nullable String guildId, @Nullable State state, @Nullable Boolean frozen,
                        @Nullable Boolean channelGone) {

        public static Patch channel(String channelId, String guildId) {
            return new Patch(channelId, guildId, null, null, null);
        }

        public static Patch state(State state) {
            return new Patch(null, null, state, null, null);
        }

        public static Patch frozen(boolean frozen) {
            return new Patch(null, null, null, frozen, null);
        }

        public static Patch gone() {
            return new Patch(null, null, null, null, true);
        }
    }

    /** What an outbox item asks the bot to do. */
    public enum OutboxKind {
        ADD, EDIT, DELETE, REDACT
    }

    /** Who wrote a relayed message, which decides the label in the channel. */
    public enum AuthorKind {
        PLAYER, STAFF, BOT, EXTERNAL
    }

    /**
     * @param seq              the case event sequence
     * @param kind             add, edit, delete or redact
     * @param messageId        the case message
     * @param authorKind       the author for an add
     * @param text             the text for an add or an edit
     * @param discordMessageId the Discord copy for an edit, delete or redact
     */
    public record OutboxItem(long seq, @NotNull OutboxKind kind, @NotNull String messageId, @Nullable AuthorKind authorKind,
                             @Nullable String text, @Nullable String discordMessageId) {
    }

    /** @param relayedSeq the ticket's cursor */
    public record Outbox(@NotNull List<OutboxItem> items, long relayedSeq) {
    }

    /** A file or sticker of a message: metadata only. */
    public record Attachment(@NotNull String kind, @Nullable String id, @Nullable String name, @Nullable String contentType,
                             @Nullable Long size, @Nullable Integer width, @Nullable Integer height) {
    }

    /** An embed of a message: its type and host only. */
    public record Embed(@NotNull String type, @Nullable String host) {
    }

    /** One message of the channel in its current state. */
    public record IngestItem(@NotNull String discordMessageId, @NotNull String authorDiscordUserId, @Nullable String authorName,
                             boolean bot, boolean hasStaffRole, long createdAt, @Nullable Long editedAt, @NotNull String text,
                             @NotNull List<Attachment> attachments, @NotNull List<Embed> embeds) {
    }

    /** A batch for one ticket. */
    public record IngestBatch(@NotNull List<IngestItem> items, @NotNull List<String> deletes, boolean complete,
                              @NotNull List<String> presentMessageIds) {

        public static IngestBatch of(List<IngestItem> items) {
            return new IngestBatch(items, List.of(), false, List.of());
        }

        public static IngestBatch delete(String discordMessageId) {
            return new IngestBatch(List.of(), List.of(discordMessageId), false, List.of());
        }
    }

    /** @param ingestedCursor the ticket's cursor afterwards */
    public record Ingested(int added, int edited, int deleted, int unchanged, @NotNull String ingestedCursor) {
    }

    /**
     * @param ticket          the ticket afterwards
     * @param protocolVersion the stored protocol version, null while undecided
     */
    public record Closed(@NotNull Ticket ticket, @Nullable Integer protocolVersion) {
    }

    /**
     * @param protocolVersion the current protocol version
     * @param stored          whether it is stored
     */
    public record ProtocolEnsured(int protocolVersion, boolean stored) {
    }
}
