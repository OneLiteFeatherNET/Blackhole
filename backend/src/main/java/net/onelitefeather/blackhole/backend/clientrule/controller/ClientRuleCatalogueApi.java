package net.onelitefeather.blackhole.backend.clientrule.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleCatalogueDTO;

public interface ClientRuleCatalogueApi {

    @Operation(
            summary = "Report a proxy's client rule catalogue",
            description = "Replaces the calling proxy's snapshot of the brands, channels, commands and protocol versions it has seen. "
                    + "Brands and channels seen from fewer than three distinct players are dropped. It expires unless the proxy "
                    + "reports again. Proxy tokens only.",
            operationId = "reportClientRuleCatalogue",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "Snapshot stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleCatalogueDTO.Stored.class)))
    @ApiResponse(responseCode = "400", description = "Invalid name or snapshot too large",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleCatalogueDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The shared cache is unavailable, retry later",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleCatalogueDTO.Error.class)))
    @Put("/")
    HttpResponse<ClientRuleCatalogueDTO> report(@Valid @Body ClientRuleCatalogueDTO.ReportRequest request);

    @Operation(
            summary = "Get the client rule catalogue",
            description = "The merged view of all live proxy snapshots, for the rule editor. available is false when none is known or the cache is down.",
            operationId = "getClientRuleCatalogue",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "The merged catalogue",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleCatalogueDTO.Response.class)))
    @Get("/")
    HttpResponse<ClientRuleCatalogueDTO.Response> get();
}
