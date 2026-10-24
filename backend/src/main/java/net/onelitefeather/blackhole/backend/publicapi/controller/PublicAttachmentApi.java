package net.onelitefeather.blackhole.backend.publicapi.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicAttachmentDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorBody;
import net.onelitefeather.blackhole.backend.security.PlayerSession;

/**
 * The player's chat files. A file is staged by the upload and becomes visible only when a message names it; the download answers only
 * for a file of the player's own visible conversation or an active share of the punishment. Every answer is {@code no-store}.
 */
public interface PublicAttachmentApi {

    @Operation(
            summary = "Upload a chat file",
            description = "Stages one file for the open appeal (PNG, JPEG, WebP, PDF or UTF-8 text, at most 10 MiB) as multipart form data. The "
                    + "type comes from the bytes, the file is scanned before it is stored, and images lose their metadata. The answer holds the "
                    + "attachmentId to send in attachmentIds of POST /public/messages. A staged file is visible to nobody until a message names "
                    + "it; an unsent one is removed after the pending lifetime.",
            operationId = "publicUploadAttachment",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The file was staged",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicAttachmentDTO.Staged.class)))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "403", description = "The Ban ID is marked leaked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "409", description = "NO_APPEAL or QUOTA",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "413", description = "TOO_LARGE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_TYPE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "422", description = "FILE_FLAGGED, FILE_ACTIVE_CONTENT or INVALID_FILE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "503", description = "UNAVAILABLE: the scanner or the store cannot answer; nothing was stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/attachments")
    HttpResponse<?> upload(@Parameter(hidden = true) PlayerSession session, @Nullable StreamingFileUpload file);

    @Operation(
            summary = "Download a chat file",
            description = "The file of a message of this conversation (the attachmentId) or the image of an active share (the shareId). Images "
                    + "are inline; every other file is an attachment. Another punishment's id, a removed message, a withdrawn share and an "
                    + "unknown id all answer 404.",
            operationId = "publicDownloadAttachment",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The file (X-Content-Type-Options: nosniff, Content-Security-Policy: sandbox, Cache-Control: private, no-store)",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "403", description = "The Ban ID is marked leaked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Get("/attachments/{id}")
    HttpResponse<?> download(@Parameter(hidden = true) PlayerSession session, @PathVariable String id);

    @Operation(
            summary = "Remove a staged chat file",
            description = "Removes a file of the open appeal that no message has sent yet. A sent file is not found here.",
            operationId = "publicDeleteAttachment",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "204", description = "The staged file was removed")
    @ApiResponse(responseCode = "401", description = "The session is missing or expired")
    @ApiResponse(responseCode = "403", description = "The Ban ID is marked leaked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PublicErrorBody.class)))
    @Delete("/attachments/{id}")
    HttpResponse<?> delete(@Parameter(hidden = true) PlayerSession session, @PathVariable String id);
}
