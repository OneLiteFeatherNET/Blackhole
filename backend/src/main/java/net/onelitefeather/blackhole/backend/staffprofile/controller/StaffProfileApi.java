package net.onelitefeather.blackhole.backend.staffprofile.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.staffprofile.dto.StaffProfileDTO;

public interface StaffProfileApi {

    @Operation(
            summary = "Own staff profile",
            description = "The caller's display name and avatar choice. An account without a name has none: the name is never "
                    + "copied from the sign-in.",
            operationId = "getOwnStaffProfile",
            tags = {"Staff profile"}
    )
    @ApiResponse(responseCode = "200", description = "The profile",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffProfileDTO.Response.class)))
    @ApiResponse(responseCode = "403", description = "The caller is a service principal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffProfileDTO.Error.class)))
    @Get("/me")
    HttpResponse<StaffProfileDTO> get(@Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Save own staff profile",
            description = "Sets the display name (2 to 32 characters, unique ignoring case, no reserved words or markup) and the "
                    + "avatar mode. The staff notice must be acknowledged before the first save.",
            operationId = "saveOwnStaffProfile",
            tags = {"Staff profile"}
    )
    @ApiResponse(responseCode = "200", description = "The saved profile",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffProfileDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The avatar mode is unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffProfileDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "The caller is a service principal",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffProfileDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "Another staff member already uses the name (kind NAME_TAKEN)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffProfileDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "The name breaks a rule (NAME_NOT_ALLOWED) or the notice was not acknowledged (NOTICE_REQUIRED)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffProfileDTO.Error.class)))
    @Put("/me")
    HttpResponse<StaffProfileDTO> save(@Body StaffProfileDTO.UpdateRequest request, @Parameter(hidden = true) Actor actor);
}
