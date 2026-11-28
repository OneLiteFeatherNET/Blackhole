package net.onelitefeather.blackhole.backend.guard.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.guard.dto.GuardSettingsDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

public interface GuardSettingsApi {

    @Operation(
            summary = "Read the join guard settings",
            description = "The unattended level, the absence threshold, the night window and its zone, with the next night window "
                    + "as epoch milliseconds.",
            operationId = "getJoinGuardSettings",
            tags = {"Guard"}
    )
    @ApiResponse(responseCode = "200", description = "The settings in force",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GuardSettingsDTO.Response.class)))
    @Get("/settings")
    HttpResponse<GuardSettingsDTO> settings();

    @Operation(
            summary = "Replace the join guard settings",
            description = "Administrators only. The whole settings object is replaced and the change is audited. The new values "
                    + "take effect at the next evaluation tick.",
            operationId = "updateJoinGuardSettings",
            tags = {"Guard"}
    )
    @ApiResponse(responseCode = "200", description = "The settings are saved and in force from the next tick",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GuardSettingsDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "A value is outside its bounds",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GuardSettingsDTO.Error.class)))
    @Put("/settings")
    HttpResponse<GuardSettingsDTO> update(@Valid @Body GuardSettingsDTO.UpdateRequest request,
                                          @Parameter(hidden = true) Actor actor);
}
