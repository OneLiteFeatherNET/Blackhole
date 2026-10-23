package net.onelitefeather.blackhole.backend.evasion.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.evasion.dto.EvasionSightingDTO;

public interface EvasionApi {

    @Operation(
            summary = "List evasion sightings for a player",
            description = "The other owners that shared a hashed network address with this owner inside the "
                    + "detection window. Only owner hashes are returned, never an IP or its token.",
            operationId = "listEvasionSightings",
            tags = {"Evasion"}
    )
    @ApiResponse(responseCode = "200", description = "The linked owners, possibly none",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvasionSightingDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The owner is not a 128 character hex hash",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvasionSightingDTO.Error.class)))
    @Get("/{owner}/sightings")
    HttpResponse<EvasionSightingDTO> sightings(@PathVariable String owner);
}
