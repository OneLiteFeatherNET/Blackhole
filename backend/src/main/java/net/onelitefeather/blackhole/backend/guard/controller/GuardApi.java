package net.onelitefeather.blackhole.backend.guard.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.guard.dto.GuardDTO;
import net.onelitefeather.blackhole.backend.guard.dto.PresenceDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

public interface GuardApi {

    @Operation(
            summary = "Read the join guard level",
            description = "The manual level, the unattended level and the effective level, which is the stricter of the two.",
            operationId = "getJoinGuard",
            tags = {"Guard"}
    )
    @ApiResponse(responseCode = "200", description = "The guard state",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GuardDTO.Response.class)))
    @Get("/state")
    HttpResponse<GuardDTO> state();

    @Operation(
            summary = "Set the manual join guard level",
            description = "OFF, STRICT or LOCKDOWN. Lowering a level is not restricted. The change is recorded with the actor. "
                    + "The proxy and the Discord bot set it for a staff member named by onBehalfOf.",
            operationId = "setJoinGuardLevel",
            tags = {"Guard"}
    )
    @ApiResponse(responseCode = "200", description = "The level is in force",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GuardDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The level is not OFF, STRICT or LOCKDOWN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GuardDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The level could not be set; try again",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GuardDTO.Error.class)))
    @Post("/level")
    HttpResponse<GuardDTO> setLevel(@Valid @Body GuardDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Report the staff online on one proxy",
            description = "Sent by each proxy every 30 seconds. The answer carries the level in force.",
            operationId = "reportJoinGuardPresence",
            tags = {"Guard"}
    )
    @ApiResponse(responseCode = "200", description = "The presence is recorded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PresenceDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The proxy id or the count is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PresenceDTO.Error.class)))
    @Post("/presence")
    HttpResponse<PresenceDTO> presence(@Valid @Body PresenceDTO.Heartbeat heartbeat);
}
