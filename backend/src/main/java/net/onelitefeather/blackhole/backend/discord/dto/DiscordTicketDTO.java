package net.onelitefeather.blackhole.backend.discord.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicketStore;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of the Discord ticket facade ({@code /discord/tickets}), used by the Discord bot only. A Discord user id appears where the
 * bot itself sent it or needs it to set a channel overwrite; a Ban ID appears only in {@link OpenRequest} and is never echoed. Discord
 * snowflakes travel as strings (they exceed what JSON numbers keep exactly).
 */
@Schema(description = "The Discord ticket facade: open, relay, ingest, close and the protocol gates")
@Serdeable
public sealed interface DiscordTicketDTO {

    /** Why a request was refused, beyond the HTTP status: a stable code the bot switches on. */
    @Schema(name = "DiscordTicketErrorCode")
    @Serdeable
    enum Code {
        /** A wrong, unknown, leaked or already bound Ban ID. One answer for all, so the reply is no oracle. */
        NOT_ELIGIBLE,
        /** The player may file only from {@code availableFrom}. */
        NOT_YET,
        /** No appeal is open and no statement was given. */
        STATEMENT_REQUIRED,
        /** The ticket is not in a state the request needs. */
        INVALID_STATE,
        /** The count or the last message id differs from the backend's tally. */
        TRANSCRIPT_MISMATCH,
        /** No stored (current) protocol: the ticket must not be archived or deleted. */
        PROTOCOL_REQUIRED,
        /** The channel is already used by another ticket. */
        CHANNEL_TAKEN,
        /** A file was sent by someone who is neither the bound player nor verified staff. */
        FILE_NOT_ACCEPTED,
        /** A file is larger than the chat limit. */
        TOO_LARGE,
        /** A file is not one of the chat types (PNG, JPEG, WebP, PDF, TXT). */
        UNSUPPORTED_TYPE,
        /** The scanner flagged the file. */
        FILE_FLAGGED,
        /** The file carries active content, or is an active document in disguise. */
        FILE_ACTIVE_CONTENT,
        /** The bytes do not match the type or are malformed. */
        INVALID_FILE,
        /** Too many files are kept for this ticket. */
        QUOTA
    }

    /**
     * Opens a ticket for the Discord user. The Ban ID is checked exactly like the portal login.
     *
     * @param discordUserId the Discord user id of the person at the modal
     * @param displayName   a name snapshot for the panel, optional
     * @param banId         as typed, with or without {@code #}
     * @param statement     1 to 2000 characters; needed when no appeal is open
     */
    @Schema(name = "DiscordTicketOpenRequest")
    @Serdeable
    @ReflectiveAccess
    record OpenRequest(@NonNull String discordUserId, @Nullable String displayName, @NonNull String banId, @Nullable String statement)
            implements DiscordTicketDTO {
    }

    /**
     * A ticket.
     *
     * @param playerDiscordUserId        the bound player's Discord user id, {@code null} once the participant was erased
     * @param leaked                     whether the case's Ban ID is marked leaked (the ticket must then be frozen)
     * @param frozen                     the freeze the bot reported
     * @param ingestedCursor             the highest Discord message id recorded, "0" when none
     * @param relayedSeq                 the highest case event {@code seq} relayed to the channel
     * @param transcriptComplete         whether {@code ticket.transcript_complete} exists
     * @param protocolVersion            the stored protocol version of the appeal, {@code null} when none
     */
    @Schema(name = "DiscordTicket")
    @Serdeable
    @ReflectiveAccess
    record Ticket(
            @NonNull UUID id, long ticketNo, @NonNull TicketState state, @NonNull UUID appealId, @Nullable String channelId,
            @Nullable String guildId, @Nullable String playerDiscordUserId, boolean leaked, boolean frozen, @NonNull String ingestedCursor,
            long relayedSeq, boolean transcriptComplete, @Nullable Integer transcriptCount, @Nullable String transcriptLastMessageId,
            @Nullable Integer protocolVersion, long createdAt
    ) implements DiscordTicketDTO {
    }

    /** A ticket opened or found again. {@code created} is false when the user already had the ticket; {@code appealFiled} when this call filed the appeal. */
    @Schema(name = "DiscordTicketOpened")
    @Serdeable
    @ReflectiveAccess
    record Opened(@NonNull Ticket ticket, boolean created, boolean appealFiled) implements DiscordTicketDTO {
    }

    /** Tickets in some states, oldest first. */
    @Schema(name = "DiscordTicketList")
    @Serdeable
    @ReflectiveAccess
    record TicketList(@JsonInclude(JsonInclude.Include.ALWAYS) @NonNull List<Ticket> tickets) implements DiscordTicketDTO {
    }

    /**
     * A change of the ticket. Every field is optional; the allowed transitions are
     * {@code PENDING_CHANNEL -> OPEN} (with {@code channelId}), {@code OPEN -> CLOSING}, {@code OPEN|CLOSING -> CLOSED} (a close that could not
     * be proven complete), {@code CLOSED -> ARCHIVED} (needs a stored protocol) and {@code ARCHIVED -> DELETED} (needs a current protocol).
     *
     * @param channelGone true when a person deleted the channel: {@code DELETED} without the protocol gate, recorded as such
     */
    @Schema(name = "DiscordTicketPatchRequest")
    @Serdeable
    @ReflectiveAccess
    record PatchRequest(
            @Nullable String channelId, @Nullable String guildId, @Nullable TicketState state, @Nullable Boolean frozen,
            @Nullable Boolean channelGone
    ) implements DiscordTicketDTO {
    }

    /**
     * The completeness proof of a closing ticket, sent after a full rescan.
     *
     * @param count         the messages the channel holds that the bot ingests (not its own)
     * @param lastMessageId the highest of their Discord ids, {@code null} or "0" for an empty channel
     * @param onBehalfOf    the staff member who closes the ticket from Discord, {@code null} for the bot's own close; the close is then
     *                      recorded under that member once the appeal is decided
     */
    @Schema(name = "DiscordTicketCloseRequest")
    @Serdeable
    @ReflectiveAccess
    record CloseRequest(int count, @Nullable String lastMessageId, @Nullable UUID onBehalfOf) implements DiscordTicketDTO {

        public CloseRequest(int count, @Nullable String lastMessageId) {
            this(count, lastMessageId, null);
        }
    }

    /**
     * The ticket closed with a verified transcript.
     *
     * @param protocolVersion the stored protocol version; {@code null} while the appeal is not decided yet (the bot must not archive then)
     * @param protocol        what the generator did: {@code STORED}, {@code UNCHANGED} or {@code NOT_DECIDED}
     */
    @Schema(name = "DiscordTicketClosed")
    @Serdeable
    @ReflectiveAccess
    record Closed(@NonNull Ticket ticket, @Nullable Integer protocolVersion, @NonNull String protocol) implements DiscordTicketDTO {
    }

    /** The current protocol version of the ticket's appeal, after {@code protocol/ensure}. */
    @Schema(name = "DiscordTicketProtocolEnsured")
    @Serdeable
    @ReflectiveAccess
    record ProtocolEnsured(int protocolVersion, boolean stored) implements DiscordTicketDTO {
    }

    // ---- ingest ----

    /**
     * A file or sticker. Never the file or its CDN URL. A file the bot uploaded and the backend accepted names its {@code attachmentId}
     * (the Discord id is then not kept); a file that was not accepted is metadata with its {@code rejected} code.
     *
     * @param kind         {@code file} (default) or {@code sticker}
     * @param id           the Discord attachment id, for metadata only
     * @param attachmentId the id {@link Attachment} returned for this file, when it was accepted
     * @param rejected     the {@link Code} the upload answered, when it was not accepted
     */
    @Schema(name = "DiscordIngestAttachment")
    @Serdeable
    @ReflectiveAccess
    record IngestAttachment(
            @Nullable String kind, @Nullable String id, @Nullable String name, @Nullable String contentType, @Nullable Long size,
            @Nullable Integer width, @Nullable Integer height, @Nullable String attachmentId, @Nullable String rejected
    ) {
    }

    /**
     * A file the backend accepted from the channel: the evidence behind the message's {@code attachmentId}.
     *
     * @param provenance {@code PLAYER} or {@code STAFF}
     */
    @Schema(name = "DiscordTicketAttachment")
    @Serdeable
    @ReflectiveAccess
    record Attachment(
            @NonNull String attachmentId, @NonNull String name, @NonNull String contentType, long size, @Nullable Integer width,
            @Nullable Integer height, @NonNull String provenance
    ) implements DiscordTicketDTO {
    }

    /** A file the bot may fetch and post: its id on the ticket's route, name, type and size. */
    @Schema(name = "DiscordOutboxFile")
    @Serdeable
    @ReflectiveAccess
    record OutboxFile(@NonNull String attachmentId, @NonNull String name, @NonNull String contentType, long size) {
    }

    /** An embed: its type and the host of its URL only. */
    @Schema(name = "DiscordIngestEmbed")
    @Serdeable
    @ReflectiveAccess
    record IngestEmbed(@NonNull String type, @Nullable String host) {
    }

    /**
     * One message of the channel in its current state. The backend decides whether it is new, an edit or already known.
     *
     * @param discordMessageId the message's snowflake
     * @param authorDiscordUserId the author's Discord user id
     * @param authorName       the author's name snapshot, optional
     * @param bot              whether Discord marks the author a bot (never the bot's own messages: it does not ingest those)
     * @param hasStaffRole     whether the author holds the staff role now
     * @param createdAt        epoch millis the message was written (Discord's timestamp)
     * @param editedAt         epoch millis of its last edit, if edited
     * @param text             JDA's display text (mentions resolved to names), up to 4000 characters
     */
    @Schema(name = "DiscordIngestItem")
    @Serdeable
    @ReflectiveAccess
    record IngestItem(
            @NonNull String discordMessageId, @NonNull String authorDiscordUserId, @Nullable String authorName, boolean bot,
            boolean hasStaffRole, long createdAt, @Nullable Long editedAt, @Nullable String text,
            @Nullable List<IngestAttachment> attachments, @Nullable List<IngestEmbed> embeds
    ) {
    }

    /**
     * A batch for one channel.
     *
     * @param items             messages seen or read from the history
     * @param deletes           Discord ids of messages the gateway reported deleted
     * @param complete          true for a full rescan from the first message: recorded messages missing from {@code presentMessageIds} are
     *                          recorded as deleted (marked {@code detectedAtCatchup})
     * @param presentMessageIds with {@code complete}: the ids of every message the channel holds
     */
    @Schema(name = "DiscordIngestRequest")
    @Serdeable
    @ReflectiveAccess
    record IngestRequest(
            @Nullable List<IngestItem> items, @Nullable List<String> deletes, boolean complete, @Nullable List<String> presentMessageIds
    ) implements DiscordTicketDTO {
    }

    /** What an ingest did. {@code ingestedCursor} is the ticket's cursor afterwards. */
    @Schema(name = "DiscordIngestResult")
    @Serdeable
    @ReflectiveAccess
    record Ingested(int added, int edited, int deleted, int unchanged, @NonNull String ingestedCursor) implements DiscordTicketDTO {
    }

    // ---- relay ----

    /**
     * One thing to do in the channel.
     *
     * @param seq              the case event's {@code seq}; acknowledge it through {@code POST .../links}
     * @param kind             what to do
     * @param messageId        the case message
     * @param authorKind       for {@code ADD}: {@code STAFF} is shown as the moderation team, {@code PLAYER} as the player (portal)
     * @param channel          for {@code ADD}: where it was written ({@code PORTAL} or {@code PANEL})
     * @param text             the current text; for {@code SHARE} an excerpt's server-rendered lines; {@code null} for a delete or redaction
     * @param discordMessageId for {@code EDIT}, {@code DELETE}, {@code REDACT} and {@code SHARE_REVOKED}: the Discord copy to change
     * @param files            for {@code ADD} and {@code SHARE}: the files to fetch and upload, in order
     */
    @Schema(name = "DiscordOutboxItem")
    @Serdeable
    @ReflectiveAccess
    record OutboxItem(
            long seq, @NonNull DiscordTicketStore.OutboxItem.Kind kind, @NonNull String messageId, @Nullable MessageAuthorKind authorKind,
            @Nullable MessageChannel channel, @Nullable String text, @Nullable String discordMessageId, long occurredAt,
            @Nullable List<OutboxFile> files
    ) {
    }

    /** The player-visible events after the requested {@code seq}, in order. */
    @Schema(name = "DiscordOutbox")
    @Serdeable
    @ReflectiveAccess
    record Outbox(@JsonInclude(JsonInclude.Include.ALWAYS) @NonNull List<OutboxItem> items, long relayedSeq) implements DiscordTicketDTO {
    }

    /**
     * Reports a relay, which advances the ticket's {@code relayed_seq}.
     *
     * @param seq              the {@link OutboxItem#seq()} handled (also for an item that needed nothing)
     * @param messageId        for an {@code ADD}: the case message that was posted
     * @param discordMessageId for an {@code ADD}: the Discord message the bot created
     */
    @Schema(name = "DiscordTicketLinkRequest")
    @Serdeable
    @ReflectiveAccess
    record LinkRequest(long seq, @Nullable UUID messageId, @Nullable String discordMessageId) implements DiscordTicketDTO {
    }

    /** The ticket's {@code relayed_seq} after a link. */
    @Schema(name = "DiscordTicketLinked")
    @Serdeable
    @ReflectiveAccess
    record Linked(long relayedSeq) implements DiscordTicketDTO {
    }

    /**
     * An expected failure.
     *
     * @param code                the stable reason
     * @param availableFrom       with {@link Code#NOT_YET}: epoch millis from which the player may file
     * @param backendCount        with {@link Code#TRANSCRIPT_MISMATCH}: the backend's own tally
     * @param backendLastMessageId with {@link Code#TRANSCRIPT_MISMATCH}: the backend's highest message id
     */
    @Schema(name = "DiscordTicketError")
    @Serdeable
    @ReflectiveAccess
    record Error(
            @NonNull String errorMessage, @NonNull ErrorKind kind, @Nullable Code code, @Nullable Long availableFrom,
            @Nullable Integer backendCount, @Nullable String backendLastMessageId
    ) implements DiscordTicketDTO, ErrorResponse {

        public static Error of(String errorMessage, ErrorKind kind) {
            return new Error(errorMessage, kind, null, null, null, null);
        }

        public static Error of(String errorMessage, ErrorKind kind, Code code) {
            return new Error(errorMessage, kind, code, null, null, null);
        }
    }
}
