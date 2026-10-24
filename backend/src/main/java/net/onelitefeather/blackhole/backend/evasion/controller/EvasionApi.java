package net.onelitefeather.blackhole.backend.evasion.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.evasion.dto.ClientFingerprintDTO;
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

    @Operation(
            summary = "Submit the client traits of a player for the client fingerprint",
            description = "The proxy sends the brand, the registered plugin channels and the legacy mod list. The backend hashes them "
                    + "with its own fingerprint key and stores only the keyed hash with the hashed owner; the raw values are never "
                    + "stored, logged or returned. Generic traits (plain vanilla, fabric, forge, neoforge) are not stored. Proxy tokens only.",
            operationId = "submitClientFingerprint",
            tags = {"Evasion"}
    )
    @ApiResponse(responseCode = "200", description = "Processed; stored is false for generic traits",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientFingerprintDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Malformed owner, oversized brand or list",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientFingerprintDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "BLACKHOLE_FINGERPRINT_KEY is not configured - client fingerprinting is off",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientFingerprintDTO.Error.class)))
    @Post("/fingerprint")
    HttpResponse<ClientFingerprintDTO> fingerprint(@Valid @Body ClientFingerprintDTO.CreateRequest request);
}
