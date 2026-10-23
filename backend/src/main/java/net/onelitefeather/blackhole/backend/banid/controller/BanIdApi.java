package net.onelitefeather.blackhole.backend.banid.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.banid.dto.BanIdDTO;

public interface BanIdApi {

    @Operation(
            summary = "Reveal the Ban ID of a punishment",
            description = "Returns the current Ban ID of a punishment: the eight character credential the player uses for the "
                    + "appeal portal. For moderators and the proxy (it shows the value on the ban screen); never cacheable "
                    + "(Cache-Control: no-store). The reference is the full identifier, or a legacy short id when it is unambiguous. "
                    + "The Ban ID is part of no other response.",
            operationId = "revealBanId",
            tags = {"Punishment"}
    )
    @ApiResponse(responseCode = "200", description = "The current Ban ID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BanIdDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment, or its Ban ID has not been issued yet",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BanIdDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BanIdDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The stored Ban ID cannot be read with the configured key",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BanIdDTO.Error.class)))
    @Get("/{reference}/ban-id")
    HttpResponse<BanIdDTO> reveal(@PathVariable String reference);
}
