package net.onelitefeather.blackhole.backend.evidence.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;

import java.util.UUID;

/**
 * Evidence endpoints. Evidence is for staff tooling behind the network boundary; there is no
 * authentication, so {@code source} (the acting staff member) is client-supplied and unverified.
 */
public interface EvidenceApi {

    @Operation(
            summary = "Attach evidence",
            description = "Attaches an anticheat record, chat excerpt, replay link or other link to a punishment, given by its "
                    + "identifier or short id, or sent ahead of the punishment under an owner hash and a correlation id "
                    + "(pass the same id as evidenceCorrelationId when applying the punishment). Files go through /evidence/file.",
            operationId = "attachEvidence",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "Evidence attached",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request, target or payload",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments; the message lists them",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "413", description = "The payload is larger than the configured limit",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "The payload contains personal data (a UUID or IP address)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Post("/evidence")
    HttpResponse<EvidenceDTO> attach(@Valid @Body EvidenceDTO.CreateRequest request);

    @Operation(
            summary = "Upload an evidence file",
            description = "Uploads a screenshot (PNG, JPEG) or video (MP4, WebM) as multipart form data. Send the text fields "
                    + "(punishment, or owner and correlationId; sourceSystem; capturedAt; description; source) before the file part. "
                    + "The type is detected from the file's bytes, images are re-encoded without metadata, and the file is virus "
                    + "scanned before it is stored.",
            operationId = "uploadEvidenceFile",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "File stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or target",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "413", description = "The file is larger than the limit for its type, or has too many pixels",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "415", description = "The file's detected type is not allowed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "The virus scanner flagged the file, or it is not a valid image",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The virus scanner or the file store is unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/evidence/file")
    HttpResponse<EvidenceDTO> upload(
            StreamingFileUpload file,
            @Nullable String punishment,
            @Nullable String owner,
            @Nullable String correlationId,
            @Nullable String sourceSystem,
            @Nullable Long capturedAt,
            @Nullable String description,
            @Nullable UUID source
    );

    @Operation(
            summary = "List the evidence of a punishment",
            description = "Lists the evidence of a punishment (identifier or short id), oldest first. Lifted and revoked evidence "
                    + "is hidden unless includeInactive is true; asking for it is audited.",
            operationId = "listPunishmentEvidence",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "One page of evidence",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.PageResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Get("/punishment/{reference}/evidence")
    HttpResponse<EvidenceDTO> listByPunishment(
            @PathVariable String reference,
            @QueryValue(defaultValue = "false") boolean includeInactive,
            @Nullable @QueryValue UUID source,
            Pageable pageable
    );

    @Operation(
            summary = "Count the valid evidence of a punishment",
            description = "The number of valid evidence items of a punishment: the only thing a player may be told. "
                    + "Lifted and revoked evidence is not counted.",
            operationId = "countPunishmentEvidence",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "The count",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.CountResponse.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Get("/punishment/{reference}/evidence/count")
    HttpResponse<EvidenceDTO> count(@PathVariable String reference);

    @Operation(
            summary = "List the evidence of an owner",
            description = "Lists every evidence item of an owner hash, for data-subject access requests. Lifted and revoked "
                    + "evidence is hidden unless includeInactive is true; asking for it is audited.",
            operationId = "listOwnerEvidence",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "One page of evidence",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.PageResponse.class)))
    @ApiResponse(responseCode = "400", description = "The owner is not a SHA-512 hash",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Get("/evidence")
    HttpResponse<EvidenceDTO> listByOwner(
            @QueryValue String owner,
            @QueryValue(defaultValue = "false") boolean includeInactive,
            @Nullable @QueryValue UUID source,
            Pageable pageable
    );

    @Operation(
            summary = "Get one evidence item",
            description = "The metadata and payload of one evidence item, whatever its status.",
            operationId = "getEvidence",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "The evidence item",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Unknown evidence",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Get("/evidence/{identifier}")
    HttpResponse<EvidenceDTO> get(@PathVariable UUID identifier, @Nullable @QueryValue UUID source);

    @Operation(
            summary = "Download an evidence file",
            description = "Streams the stored file through the backend (there are no presigned links). Supports a single HTTP "
                    + "Range so videos can be scrubbed. Sent with nosniff, a sandboxing Content-Security-Policy and no-store; "
                    + "images and videos are served inline, so the type is the detected one.",
            operationId = "downloadEvidenceFile",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "The whole file",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "The requested byte range",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "Unknown evidence, or it has no file",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "416", description = "The range starts behind the end of the file")
    @ApiResponse(responseCode = "503", description = "The file store is unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Get("/evidence/{identifier}/content")
    HttpResponse<?> content(@PathVariable UUID identifier, @Nullable @Header("Range") String range, @Nullable @QueryValue UUID source);

    @Operation(
            summary = "Delete an evidence item",
            description = "Deletes one item and its file. A reason (ERASURE_REQUEST, WRONG_EVIDENCE, OTHER) is required and "
                    + "recorded, with the optional note, in the audit trail. Nothing is erased automatically for "
                    + "data-subject requests: the operator decides each one.",
            operationId = "deleteEvidence",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "Deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.DeleteResponse.class)))
    @ApiResponse(responseCode = "400", description = "The reason is missing or invalid; nothing was deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown evidence",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The file store is unavailable; the item was not deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Delete("/evidence/{identifier}")
    HttpResponse<EvidenceDTO> delete(
            @PathVariable UUID identifier,
            @Nullable @QueryValue String reason,
            @Nullable @QueryValue String note,
            @Nullable @QueryValue UUID source
    );

    @Operation(
            summary = "Delete all evidence of an owner",
            description = "Deletes every evidence item and file of an owner hash (data-subject erasure). Same required reason "
                    + "and per-item audit as deleting one item.",
            operationId = "deleteOwnerEvidence",
            tags = {"Evidence"}
    )
    @ApiResponse(responseCode = "200", description = "Deleted; the count may be zero",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.DeleteResponse.class)))
    @ApiResponse(responseCode = "400", description = "The reason is missing or invalid, or the owner is not a hash; nothing was deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The file store is unavailable; deletion stopped at the first file it could not delete",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDTO.Error.class)))
    @Delete("/evidence/owner/{owner}")
    HttpResponse<EvidenceDTO> deleteByOwner(
            @PathVariable String owner,
            @Nullable @QueryValue String reason,
            @Nullable @QueryValue String note,
            @Nullable @QueryValue UUID source
    );
}
