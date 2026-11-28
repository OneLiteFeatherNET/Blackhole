package net.onelitefeather.blackhole.backend.rollback.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.rollback.RollbackDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.UUID;

public interface RollbackApi {

    @Operation(
            summary = "Preview a rollback of one staff member's punishments",
            description = "Counts what a rollback would undo for one staff member, or for the Minecraft account of an unlinked "
                    + "in-game actor, in a time window. Nothing is revoked. The matching revocable punishments are frozen into "
                    + "a job that expires after the preview time-to-live. The admin who asks is the caller of the token.",
            operationId = "previewRollback",
            tags = {"Rollback"}
    )
    @ApiResponse(responseCode = "201", description = "The preview and its frozen job",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request, or the selector is a service principal such as the proxy",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "The preview matches more punishments than the maximum; narrow the window",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @Post("/preview")
    HttpResponse<RollbackDTO> preview(
            @Body RollbackDTO.PreviewRequest request,
            @Parameter(hidden = true) Actor actor
    );

    @Operation(
            summary = "Start a previewed rollback",
            description = "Confirms a preview with a reason and starts it. A scheduled runner then revokes the frozen punishments "
                    + "one batch at a time, each through the manual revoke path. The Idempotency-Key header is required; a repeat "
                    + "with the same key answers the same job and writes nothing. Poll GET /admin/rollback/{id} for progress.",
            operationId = "executeRollback",
            tags = {"Rollback"}
    )
    @ApiResponse(responseCode = "202", description = "The rollback is running, or the same start was repeated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Started.class)))
    @ApiResponse(responseCode = "400", description = "Missing Idempotency-Key, or a reason that is not 1 to 500 characters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "No such rollback",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The preview expired, or the rollback was already started",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @Post("/{id}/execute")
    HttpResponse<RollbackDTO> execute(
            @PathVariable UUID id,
            @Body RollbackDTO.ExecuteRequest request,
            @Header("Idempotency-Key") @Nullable @Parameter(name = "Idempotency-Key", description = "1 to 64 characters; required") String idempotencyKey
    );

    @Operation(
            summary = "Get the progress of a rollback",
            description = "The job's state and how many of its items are pending, revoked, skipped or failed.",
            operationId = "getRollback",
            tags = {"Rollback"}
    )
    @ApiResponse(responseCode = "200", description = "The progress",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Progress.class)))
    @ApiResponse(responseCode = "404", description = "No such rollback",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @Get("/{id}")
    HttpResponse<RollbackDTO> get(@PathVariable UUID id);

    @Operation(
            summary = "Cancel a rollback",
            description = "Stops a preview or a running rollback before its next item. Items already revoked stay revoked.",
            operationId = "cancelRollback",
            tags = {"Rollback"}
    )
    @ApiResponse(responseCode = "200", description = "The rollback was cancelled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Cancelled.class)))
    @ApiResponse(responseCode = "404", description = "No such rollback",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The rollback is already completed or cancelled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RollbackDTO.Error.class)))
    @Post("/{id}/cancel")
    HttpResponse<RollbackDTO> cancel(@PathVariable UUID id);
}
