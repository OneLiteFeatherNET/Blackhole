package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.List;
import java.util.UUID;

/**
 * The Discord ticket facade. Called by the Discord bot only (role {@code DISCORD}; staff and the proxy are denied). The bot holds no state:
 * tickets, message links and cursors live here. A Ban ID is accepted by {@code POST /discord/tickets} and nowhere echoed; Discord snowflakes
 * travel as strings.
 */
public interface DiscordTicketApi {

    @Operation(
            summary = "Open a ticket with a private Ban ID",
            description = "The Ban ID is checked exactly like the portal login. A wrong, unknown, leaked or already bound Ban ID all answer 404 NOT_ELIGIBLE, "
                    + "so the reply is no oracle. An open appeal is reused; otherwise a statement (1 to 2000 characters) files one under the portal's "
                    + "rules, and an appeal that is not possible yet answers 409 NOT_YET with availableFrom and records nothing. The user who already "
                    + "has the ticket gets it again with created false. The ticket starts PENDING_CHANNEL; ticket.opened is on the timeline.",
            operationId = "openDiscordTicket",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Opened.class)))
    @ApiResponse(responseCode = "400", description = "Malformed request, or no statement / an invalid statement (STATEMENT_REQUIRED)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "NOT_ELIGIBLE: the uniform failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "NOT_YET: the appeal is possible from availableFrom",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Post
    HttpResponse<DiscordTicketDTO> open(@Body DiscordTicketDTO.OpenRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "List tickets",
            description = "Tickets in the given states, oldest first; without a state the pending, open, closing and closed ones. The bot reads them "
                    + "at start to catch up.",
            operationId = "listDiscordTickets",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The tickets",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.TicketList.class)))
    @Get
    HttpResponse<DiscordTicketDTO> list(@Nullable @QueryValue List<TicketState> state);

    @Operation(
            summary = "Read a ticket",
            operationId = "getDiscordTicket",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Ticket.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Get("/{id}")
    HttpResponse<DiscordTicketDTO> get(@PathVariable UUID id);

    @Operation(
            summary = "Report the channel, change the state or the freeze",
            description = "Reports the channel id (PENDING_CHANNEL becomes OPEN), mirrors the freeze, moves the state (OPEN to CLOSING to CLOSED, CLOSED to "
                    + "ARCHIVED, ARCHIVED to DELETED) or reports that a person deleted the channel (channelGone). ARCHIVED needs a stored protocol and "
                    + "DELETED a current one, both answered 409 PROTOCOL_REQUIRED otherwise. Repeating a change that is already true is a no-op.",
            operationId = "patchDiscordTicket",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The ticket afterwards",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Ticket.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATE, PROTOCOL_REQUIRED or CHANNEL_TAKEN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Patch("/{id}")
    HttpResponse<DiscordTicketDTO> patch(@PathVariable UUID id, @Body DiscordTicketDTO.PatchRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Events to relay into the channel",
            description = "The player-visible events of the case after afterSeq (the ticket's relayed_seq when omitted), with their text, in order. "
                    + "A message written in Discord is never in it. Acknowledge each with POST .../links.",
            operationId = "getDiscordTicketOutbox",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The events",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Outbox.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Get("/{id}/outbox")
    HttpResponse<DiscordTicketDTO> outbox(@PathVariable UUID id, @Nullable @QueryValue Long afterSeq, @Nullable @QueryValue Integer limit);

    @Operation(
            summary = "Ingest the messages of the channel",
            description = "Records what was written in the channel, idempotent by Discord message id: a new message becomes message.added (channel DISCORD, "
                    + "the Discord timestamp as time), a known one with a different text message.edited, a reported deletion message.deleted (contentUnavailable "
                    + "when it was never recorded). With complete true and presentMessageIds, recorded messages missing from the channel are recorded as "
                    + "deleted (detectedAtCatchup). Never post the bot's own messages.",
            operationId = "ingestDiscordTicketMessages",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "What was recorded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Ingested.class)))
    @ApiResponse(responseCode = "400", description = "Malformed batch (nothing was recorded)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The channel is deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Post("/{id}/messages")
    HttpResponse<DiscordTicketDTO> ingest(@PathVariable UUID id, @Body DiscordTicketDTO.IngestRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Report a relay",
            description = "Advances the ticket's relayed_seq to at least seq and, for a posted message, links the case message to the Discord message. "
                    + "The cursor only moves forward; linking twice changes nothing.",
            operationId = "linkDiscordTicketMessage",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The cursor afterwards",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Linked.class)))
    @ApiResponse(responseCode = "400", description = "Half a link or a malformed id",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Post("/{id}/links")
    HttpResponse<DiscordTicketDTO> link(@PathVariable UUID id, @Body DiscordTicketDTO.LinkRequest request);

    @Operation(
            summary = "Close with the completeness proof",
            description = "Accepted only when count and lastMessageId equal the backend's own tally of the channel's messages: records "
                    + "ticket.transcript_complete and, in the same request, generates and stores the protocol, answering its version. A mismatch answers "
                    + "409 TRANSCRIPT_MISMATCH and records nothing. 503 when the protocol could not be stored (the event stays; repeat the call, it is idempotent). "
                    + "protocolVersion is null while the appeal is undecided: do not archive then.",
            operationId = "closeDiscordTicket",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The transcript is complete",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Closed.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "TRANSCRIPT_MISMATCH (with the backend's tally) or INVALID_STATE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The protocol could not be stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Post("/{id}/close")
    HttpResponse<DiscordTicketDTO> close(@PathVariable UUID id, @Body DiscordTicketDTO.CloseRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Make sure the protocol is current",
            description = "Regenerates the protocol when the conversation changed since the stored version (forced, no settle delay) and answers the "
                    + "current version. Call it before deleting a channel; the delete is refused with 409 PROTOCOL_REQUIRED otherwise.",
            operationId = "ensureDiscordTicketProtocol",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The current version",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.ProtocolEnsured.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "PROTOCOL_REQUIRED: the appeal is undecided or the transcript is not verified complete",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The protocol could not be stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Post("/{id}/protocol/ensure")
    HttpResponse<DiscordTicketDTO> ensureProtocol(@PathVariable UUID id);

    @Operation(
            summary = "Store a file the channel sent",
            description = "Only the bound player and verified staff may send a file; anyone else gets 403 FILE_NOT_ACCEPTED. The file goes through the chat "
                    + "pipeline (scan, type, size, sanitising) and is stored bound to the case, idempotent per Discord attachment id: a replay answers the "
                    + "same attachmentId. The bot then ingests the message naming that attachmentId. The Discord id is not stored.",
            operationId = "uploadDiscordTicketAttachment",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The stored file",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Attachment.class)))
    @ApiResponse(responseCode = "400", description = "A file the pipeline refused (TOO_LARGE, UNSUPPORTED_TYPE, INVALID_FILE, FILE_FLAGGED, FILE_ACTIVE_CONTENT) or a malformed request",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "FILE_NOT_ACCEPTED: the author is neither the bound player nor verified staff",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown ticket",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "QUOTA, or the channel is deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The scanner or the store is unavailable: nothing was stored, retry later",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/{id}/attachments")
    HttpResponse<DiscordTicketDTO> uploadAttachment(@PathVariable UUID id, @Nullable StreamingFileUpload file,
                                                    @Part("discordAttachmentId") String discordAttachmentId,
                                                    @Part("authorDiscordUserId") String authorDiscordUserId, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Fetch a file of the ticket's case",
            description = "A file of a message the player can see, or the image of an active share, of this ticket's case. Served with the chat "
                    + "headers (inline only for PNG, JPEG and WebP; nosniff; sandbox; no-store). Anything else is 404.",
            operationId = "getDiscordTicketAttachment",
            tags = {"Discord tickets"}
    )
    @ApiResponse(responseCode = "200", description = "The file",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "Unknown ticket or file",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The file could not be read",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiscordTicketDTO.Error.class)))
    @Get("/{id}/attachments/{attachmentId}")
    HttpResponse<?> attachment(@PathVariable UUID id, @PathVariable UUID attachmentId);
}
