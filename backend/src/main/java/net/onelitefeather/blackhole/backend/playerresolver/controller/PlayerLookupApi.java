package net.onelitefeather.blackhole.backend.playerresolver.controller;

import net.onelitefeather.blackhole.backend.playerresolver.dto.PlayerResolveDTO;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

public interface PlayerLookupApi {

    @Operation(
            description = "Resolve a player name to a UUID via the configured resolver chain (Otis, Mojang, NameMC)",
            operationId = "resolvePlayer",
            tags = {"Player"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The player was resolved",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PlayerResolveDTO.Response.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "No resolver could find a player with that name",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PlayerResolveDTO.Error.class)
            )
    )
    @Get("/resolve/{name}")
    HttpResponse<PlayerResolveDTO> resolve(@PathVariable String name);
}
