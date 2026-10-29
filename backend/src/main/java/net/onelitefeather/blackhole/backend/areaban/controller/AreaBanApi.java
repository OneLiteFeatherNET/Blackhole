package net.onelitefeather.blackhole.backend.areaban.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.areaban.dto.AreaBanDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

public interface AreaBanApi {

    @Operation(
            summary = "List the area bans that apply now",
            description = "Every unlifted ban of an ASN or a country that has not expired. With includeInactive=true, the lifted "
                    + "and expired bans are listed too, newest first.",
            operationId = "listAreaBans",
            tags = {"Area bans"}
    )
    @ApiResponse(responseCode = "200", description = "The area bans",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.ListResponse.class)))
    @Get("/area-bans")
    HttpResponse<AreaBanDTO> list(@QueryValue(defaultValue = "false") @Parameter(name = "includeInactive",
            description = "true to include the lifted and expired bans") boolean includeInactive);

    @Operation(
            summary = "Ban an autonomous system or a country",
            description = "A moderator may ban one ASN for up to seven days. An admin may ban a country, or an ASN without a limit.",
            operationId = "createAreaBan",
            tags = {"Area bans"}
    )
    @ApiResponse(responseCode = "200", description = "The ban applies",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The kind, the value, the reason or the duration is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_KIND: a moderator asked for a country. FORBIDDEN_DURATION: a moderator asked for a permanent ban, or one over seven days",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "CONFLICT: the area is already banned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Error.class)))
    @Post("/area-bans")
    HttpResponse<AreaBanDTO> create(@Valid @Body AreaBanDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Lift an area ban",
            description = "A reason is required. The ban stays in the history.",
            operationId = "liftAreaBan",
            tags = {"Area bans"}
    )
    @ApiResponse(responseCode = "200", description = "The ban is lifted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The id or the reason is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: no such area ban",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "CONFLICT: the ban is already lifted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AreaBanDTO.Error.class)))
    @Post("/area-bans/{id}/lift")
    HttpResponse<AreaBanDTO> lift(@PathVariable String id, @Valid @Body AreaBanDTO.LiftRequest request,
                                  @Parameter(hidden = true) Actor actor);
}
