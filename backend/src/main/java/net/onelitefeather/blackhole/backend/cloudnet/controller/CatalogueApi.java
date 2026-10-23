package net.onelitefeather.blackhole.backend.cloudnet.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.cloudnet.dto.CatalogueDTO;

public interface CatalogueApi {

    @Operation(
            summary = "Report a proxy's CloudNet catalogue",
            description = "Replaces the calling proxy's snapshot of CloudNet tasks, groups and services. It expires after "
                    + "15 minutes unless the proxy reports again. Proxy tokens only.",
            operationId = "reportCloudNetCatalogue",
            tags = {"CloudNet"}
    )
    @ApiResponse(responseCode = "200", description = "Snapshot stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogueDTO.Stored.class)))
    @ApiResponse(responseCode = "400", description = "Invalid name or snapshot too large",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogueDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The shared cache is unavailable, retry later",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogueDTO.Error.class)))
    @Put("/")
    HttpResponse<CatalogueDTO> report(@Valid @Body CatalogueDTO.ReportRequest request);

    @Operation(
            summary = "Get the CloudNet catalogue",
            description = "The merged view of all live proxy snapshots. available is false when none is known or the cache is down.",
            operationId = "getCloudNetCatalogue",
            tags = {"CloudNet"}
    )
    @ApiResponse(responseCode = "200", description = "The merged catalogue",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CatalogueDTO.Response.class)))
    @Get("/")
    HttpResponse<CatalogueDTO.Response> get();
}
