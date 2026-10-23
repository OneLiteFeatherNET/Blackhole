package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.http.HttpResponse;
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
