package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.dto.StaffLinkDTO;

import java.util.UUID;

public interface AdminStaffLinkApi {

    @Operation(
            summary = "List staff links",
            description = "Every staff member's linked Minecraft account, with the staff member's display name.",
            operationId = "listStaffLinks",
            tags = {"Staff link"}
    )
    @ApiResponse(responseCode = "200", description = "The links, oldest first",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.ListResponse.class)))
    @Get
    HttpResponse<StaffLinkDTO> list();

    @Operation(
            summary = "Unlink a staff member",
            description = "Removes the link of any staff member. Actions already attributed to them stay attributed. "
                    + "Audited with the admin's own object id.",
            operationId = "unlinkStaffLink",
            tags = {"Staff link"}
    )
    @ApiResponse(responseCode = "200", description = "The link was removed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.RemovedResponse.class)))
    @ApiResponse(responseCode = "404", description = "That staff member has no link",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffLinkDTO.Error.class)))
    @Delete("/{oid}")
    HttpResponse<StaffLinkDTO> unlink(@PathVariable UUID oid, @Parameter(hidden = true) Actor actor);
}
