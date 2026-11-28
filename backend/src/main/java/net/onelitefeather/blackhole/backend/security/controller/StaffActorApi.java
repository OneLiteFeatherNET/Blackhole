package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.dto.StaffActorDTO;

import java.util.UUID;

public interface StaffActorApi {

    @Operation(
            summary = "List staff actors",
            description = "Every verified staff principal the backend has seen, with the display name kept for it "
                    + "(absent once anonymized), its kind and when it was first and last seen. Most recently seen first.",
            operationId = "listStaffActors",
            tags = {"Auth"}
    )
    @ApiResponse(responseCode = "200", description = "The known staff principals",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffActorDTO.ListResponse.class)))
    @Get
    HttpResponse<StaffActorDTO> list();

    @Operation(
            summary = "One staff actor",
            description = "The known staff principal with this object id, with the fields of the list item.",
            operationId = "getStaffActor",
            tags = {"Auth"}
    )
    @ApiResponse(responseCode = "200", description = "The staff actor",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffActorDTO.Item.class)))
    @ApiResponse(responseCode = "400", description = "The object id is not a UUID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffActorDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: unknown staff actor",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffActorDTO.Error.class)))
    @Get("/{oid}")
    HttpResponse<StaffActorDTO> get(@PathVariable UUID oid);

    @Operation(
            summary = "Anonymize a staff actor",
            description = "Clears the stored display name of a staff principal (leaving staff, erasure request). The "
                    + "object id and every record that references it stay. The action is audited with the admin's own object id.",
            operationId = "anonymizeStaffActor",
            tags = {"Auth"}
    )
    @ApiResponse(responseCode = "200", description = "The display name was cleared",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffActorDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Unknown staff actor",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffActorDTO.Error.class)))
    @Post("/{oid}/anonymize")
    HttpResponse<StaffActorDTO> anonymize(@PathVariable UUID oid, @Parameter(hidden = true) Actor actor);
}
