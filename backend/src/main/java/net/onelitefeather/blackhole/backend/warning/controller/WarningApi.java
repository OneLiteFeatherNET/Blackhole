package net.onelitefeather.blackhole.backend.warning.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.warning.dto.WarningDTO;

public interface WarningApi {

    @Operation(
            summary = "Get a warning",
            description = "One warning with its reason, the staff member who gave it, when, and when the player confirmed it "
                    + "(null while pending). The proxy reads the reason here before it shows the warning in game.",
            operationId = "getWarning",
            tags = {"Warning"}
    )
    @ApiResponse(responseCode = "200", description = "The warning",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WarningDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "No such warning",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WarningDTO.Error.class)))
    @Get("/{id}")
    HttpResponse<WarningDTO> get(@PathVariable String id);

    @Operation(
            summary = "List a player's pending warnings",
            description = "The warnings of one player that nobody has confirmed yet, oldest first, at most limit (default 10, "
                    + "at most 50). A player with more pending warnings sees the rest on the next login. Only the proxy may read it.",
            operationId = "listPendingWarnings",
            tags = {"Warning"}
    )
    @ApiResponse(responseCode = "200", description = "The pending warnings",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WarningDTO.Pending.class)))
    @ApiResponse(responseCode = "400", description = "Invalid owner (a SHA-512 hash) or limit (1 to 50)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WarningDTO.Error.class)))
    @Get("/pending")
    HttpResponse<WarningDTO> pending(
            @QueryValue @Schema(description = "The hashed player, SHA-512 as 128 hex characters") String owner,
            @Nullable @QueryValue @Schema(description = "At most this many warnings; 10 when absent, at most 50") Integer limit
    );

    @Operation(
            summary = "Record the player's confirmation of a warning",
            description = "Called by the proxy when the player clicks Acknowledge. The first confirmation sets the time and is "
                    + "recorded on the case; a repeat keeps the first time and answers the same. A warning of another player is "
                    + "reported as unknown. Only the proxy may call it.",
            operationId = "acknowledgeWarning",
            tags = {"Warning"}
    )
    @ApiResponse(responseCode = "204", description = "The confirmation is stored")
    @ApiResponse(responseCode = "400", description = "Invalid owner",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WarningDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "No such warning for this player",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WarningDTO.Error.class)))
    @Post("/{id}/acknowledge")
    HttpResponse<WarningDTO> acknowledge(@PathVariable String id, @Body WarningDTO.AcknowledgeRequest request);
}
