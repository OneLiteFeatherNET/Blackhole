package net.onelitefeather.blackhole.backend.evidence.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

/**
 * The staff upload of a chat file. The acting staff member is the caller of the verified token; the file is staged (scanned, sniffed,
 * stored) and in no listing, count or conversation until a message names its id in {@code attachmentIds}.
 */
public interface ChatAttachmentApi {

    @Operation(
            summary = "Upload a file for a chat message",
            description = "Stages one file (PNG, JPEG, WebP, PDF or UTF-8 text, at most 10 MiB) for the case's conversation as multipart form "
                    + "data. The type is detected from the bytes, the file is virus scanned before it is stored, images lose their metadata, "
                    + "and a PDF that names active content is refused. The answer holds the attachmentId to send in attachmentIds of "
                    + "POST /case/{reference}/messages; the player sees the file once that message is posted, and the panel must warn "
                    + "the moderator before sending. A quota bounds how many files wait and how many a case holds.",
            operationId = "uploadCaseAttachment",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The file was staged",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Staged.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "QUOTA: too many files wait or the case holds the maximum, or the short id is ambiguous",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than the limit, or an image has too many pixels",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "415", description = "The detected type is not allowed (video, archives, SVG, other)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "FILE_FLAGGED by the virus scanner, FILE_ACTIVE_CONTENT in a PDF, or not a valid image",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The virus scanner or the file store is unavailable (nothing is stored)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/{reference}/attachments")
    HttpResponse<EvidenceDTO> upload(
            @PathVariable String reference,
            StreamingFileUpload file,
            @Parameter(hidden = true) Actor actor
    );
}
