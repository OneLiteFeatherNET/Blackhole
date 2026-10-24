package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.UUID;

/**
 * Conversation endpoints of a case: messages to the player, the Ban ID leak rule, erasure and the
 * transcript export. A case is addressed by the punishment's identifier or short id. The acting staff
 * member is always the caller of the verified token; nothing in a body names an actor, a visibility,
 * a channel or a Ban ID, and no response carries a Ban ID.
 */
public interface CaseConversationApi {

    @Operation(
            summary = "Reply to the player",
            description = "Adds a message of 1 to 2000 characters that the player can read (visibility is always PLAYER; a body field "
                    + "cannot change that, staff-internal text is a note). It must not contain a UUID, an IP address or a Ban ID. "
                    + "The optional Idempotency-Key makes a repeated post a no-op that answers with the first message.",
            operationId = "postCaseMessage",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The message was stored (repeated: true when the key had posted it before)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.MessageResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid text or Idempotency-Key",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Post("/{reference}/messages")
    HttpResponse<CaseConversationDTO> postMessage(
            @PathVariable String reference,
            @Body CaseConversationDTO.MessageRequest request,
            @Nullable @Header("Idempotency-Key") String idempotencyKey,
            @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Correct a staff message",
            description = "Appends a new version of a staff message; the earlier versions stay. Only the verified author or an admin may, "
                    + "never a player's message, and not once the message is deleted or redacted.",
            operationId = "editCaseMessage",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The message was edited",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.MessageResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid text",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "Not the author (and not an admin), or a player's message",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or message",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The message was deleted or redacted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Put("/{reference}/messages/{messageId}")
    HttpResponse<CaseConversationDTO> editMessage(
            @PathVariable String reference, @PathVariable UUID messageId, @Body CaseConversationDTO.MessageRequest request,
            @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Delete a staff message",
            description = "Appends message.deleted: the player sees the message removed, staff see its last text marked deleted. "
                    + "Same rules as an edit.",
            operationId = "deleteCaseMessage",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The message was deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.MessageResponse.class)))
    @ApiResponse(responseCode = "403", description = "Not the author (and not an admin), or a player's message",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or message",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The message was already deleted or redacted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Delete("/{reference}/messages/{messageId}")
    HttpResponse<CaseConversationDTO> deleteMessage(
            @PathVariable String reference, @PathVariable UUID messageId, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Redact a message",
            description = "Removes the text of every version of a message for data protection, keeping type, time, author kind and "
                    + "visibility, and appends message.redacted. The reason is a code. Repeating it is harmless.",
            operationId = "redactCaseMessage",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The message was redacted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.RedactResponse.class)))
    @ApiResponse(responseCode = "400", description = "No reason",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or message",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Post("/{reference}/messages/{messageId}/redact")
    HttpResponse<CaseConversationDTO> redactMessage(
            @PathVariable String reference, @PathVariable UUID messageId, @Body CaseConversationDTO.RedactRequest request,
            @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Erase the player's side of the conversation",
            description = "Redacts every message the player wrote in this case in one statement (staff messages stay) and appends "
                    + "message.redacted for each. The reason is a code.",
            operationId = "eraseCasePlayerMessages",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The player messages were redacted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.EraseResponse.class)))
    @ApiResponse(responseCode = "400", description = "No reason",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Post("/{reference}/transcript/erase-player")
    HttpResponse<CaseConversationDTO> erasePlayerMessages(
            @PathVariable String reference, @Body CaseConversationDTO.RedactRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Mark the Ban ID leaked",
            description = "Records ban_id.leak_marked with a reason code and sets the leak state of the case. Marking an already leaked "
                    + "Ban ID changes nothing (changed: false). Before an appeal the right to appeal is forfeited, after filing the ticket "
                    + "is frozen for the player. The Ban ID itself is never in the request, the response or the event.",
            operationId = "markCaseBanIdLeaked",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The leak is marked (changed: false when it already was)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.LeakResponse.class)))
    @ApiResponse(responseCode = "400", description = "No reason",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Post("/{reference}/ban-id/leak")
    HttpResponse<CaseConversationDTO> markBanIdLeaked(
            @PathVariable String reference, @Body CaseConversationDTO.LeakRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Reissue the Ban ID",
            description = "Replaces the Ban ID, raises its generation, clears the leak state and records ban_id.reissued with the "
                    + "generation numbers. The old Ban ID stops working at once. The new one is not returned: reveal it with "
                    + "GET /punishment/{reference}/ban-id.",
            operationId = "reissueCaseBanId",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "A new Ban ID was issued",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.ReissueResponse.class)))
    @ApiResponse(responseCode = "400", description = "No reason",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The punishment has no Ban ID yet",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "No free Ban ID could be drawn",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Post("/{reference}/ban-id/reissue")
    HttpResponse<CaseConversationDTO> reissueBanId(
            @PathVariable String reference, @Body CaseConversationDTO.ReissueRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Export the transcript",
            description = "The conversation and lifecycle of the case in order, as a JSON or text attachment. Notes are left out unless "
                    + "includeNotes is set; redacted entries are markers without text. Every export appends transcript.exported "
                    + "(actor, format, entry count, no content) first. The response is never cached.",
            operationId = "exportCaseTranscript",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The transcript file (Content-Disposition: attachment, Cache-Control: no-store)",
            content = {
                    @Content(mediaType = "application/json", schema = @Schema(type = "string")),
                    @Content(mediaType = "text/plain", schema = @Schema(type = "string"))
            })
    @ApiResponse(responseCode = "400", description = "Unknown format",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseConversationDTO.Error.class)))
    @Get("/{reference}/transcript")
    HttpResponse<?> exportTranscript(
            @PathVariable String reference,
            @Nullable @QueryValue String format,
            @Nullable @QueryValue Boolean includeNotes,
            @Parameter(hidden = true) Actor actor);
}
