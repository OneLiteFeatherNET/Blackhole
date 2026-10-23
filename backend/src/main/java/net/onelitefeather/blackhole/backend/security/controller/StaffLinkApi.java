package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.dto.StaffLinkDTO;

import java.util.UUID;

public interface StaffLinkApi {

    @Operation(
            summary = "Issue a Minecraft link code",
            description = "Issues the signed-in staff member a short-lived, single-use code (8 characters, shown as "
                    + "ABCD-EFGH) to type in game with /blackhole link. A new code replaces the caller's previous one. "
                    + "Only a signed-in person can link; a service principal gets 403. The code is returned only here "
                    + "and stored only as a hash.",
            operationId = "issueStaffLinkCode",
            tags = {"Staff link"}
    )
    @ApiResponse(responseCode = "200", description = "The code and when it expires",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.CodeResponse.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not a signed-in person",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.Error.class)))
    @Post("/code")
    HttpResponse<StaffLinkDTO> issueCode(@Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Own Minecraft link status",
            description = "Whether the caller has a linked Minecraft account. Never contains a code.",
            operationId = "getOwnStaffLink",
            tags = {"Staff link"}
    )
    @ApiResponse(responseCode = "200", description = "The link status",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.StatusResponse.class)))
    @Get("/me")
    HttpResponse<StaffLinkDTO> status(@Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Unlink own Minecraft account",
            description = "Removes the caller's link. Actions already attributed to the caller stay attributed.",
            operationId = "unlinkOwnStaffLink",
            tags = {"Staff link"}
    )
    @ApiResponse(responseCode = "200", description = "The link was removed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.RemovedResponse.class)))
    @ApiResponse(responseCode = "404", description = "The caller has no link",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.Error.class)))
    @Delete("/me")
    HttpResponse<StaffLinkDTO> unlink(@Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Confirm a link code",
            description = "Called by the Velocity proxy only. Binds the Minecraft UUID in onBehalfOf (the player who "
                    + "typed the code) to the staff member the code was issued to, replacing any earlier link of "
                    + "either side. A wrong, expired or already used code is answered identically with INVALID_CODE.",
            operationId = "confirmStaffLink",
            tags = {"Staff link"}
    )
    @ApiResponse(responseCode = "200", description = "The link now exists",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.LinkedResponse.class)))
    @ApiResponse(responseCode = "400", description = "onBehalfOf is missing, or the code is invalid, expired or used (kind INVALID_CODE)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "A concurrent confirmation took the account; try again",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.Error.class)))
    @Post("/confirm")
    HttpResponse<StaffLinkDTO> confirm(@Body StaffLinkDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Who is a Minecraft account",
            description = "Called by the Velocity proxy for /blackhole whoami: whether the account is linked and to "
                    + "which staff member.",
            operationId = "lookupStaffLinkByMinecraft",
            tags = {"Staff link"}
    )
    @ApiResponse(responseCode = "200", description = "The link status of the account",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.LookupResponse.class)))
    @Get("/by-minecraft/{uuid}")
    HttpResponse<StaffLinkDTO> byMinecraft(@PathVariable UUID uuid);
}
