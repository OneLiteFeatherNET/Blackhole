package net.onelitefeather.blackhole.backend.appealconditions.controller;

import io.micronaut.http.annotation.PathVariable;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.appealconditions.dto.AppealConditionsDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.List;

public interface AppealConditionsApi {

    @Operation(
            summary = "The current appeal conditions",
            description = "The newest version of the conditions a player confirms before filing an appeal.",
            operationId = "currentAppealConditions",
            tags = {"AppealConditions"}
    )
    @ApiResponse(responseCode = "200", description = "The current version",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealConditionsDTO.Response.class)))
    @Get("/")
    HttpResponse<AppealConditionsDTO> current();

    @Operation(
            summary = "All versions of the appeal conditions",
            description = "Every version, newest first. Versions are never changed or removed.",
            operationId = "listAppealConditionVersions",
            tags = {"AppealConditions"}
    )
    @ApiResponse(responseCode = "200", description = "The versions",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = AppealConditionsDTO.Response.class))))
    @Get("/versions")
    HttpResponse<List<AppealConditionsDTO.Response>> versions();

    @Operation(
            summary = "One version of the appeal conditions",
            description = "The text of one saved version, by its number. A saved version never changes.",
            operationId = "getAppealConditionVersion",
            tags = {"AppealConditions"}
    )
    @ApiResponse(responseCode = "200", description = "The version",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealConditionsDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The version is not an integer of 1 or more",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealConditionsDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: there is no such version",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealConditionsDTO.Error.class)))
    @Get("/versions/{version}")
    HttpResponse<AppealConditionsDTO> version(@PathVariable int version);

    @Operation(
            summary = "Save the appeal conditions as a new version",
            description = "Creates the next version; no version is ever overwritten.",
            operationId = "saveAppealConditions",
            tags = {"AppealConditions"}
    )
    @ApiResponse(responseCode = "200", description = "The new version",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealConditionsDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The text is empty, too long or has a control character",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealConditionsDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "Another save took the version number; review and save again",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppealConditionsDTO.Error.class)))
    @Post("/")
    HttpResponse<AppealConditionsDTO> save(@Nullable @Body AppealConditionsDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);
}
