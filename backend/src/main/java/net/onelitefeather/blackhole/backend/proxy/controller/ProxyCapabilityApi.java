package net.onelitefeather.blackhole.backend.proxy.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.proxy.dto.ProxyCapabilityDTO;

public interface ProxyCapabilityApi {

    @Operation(
            summary = "Report a proxy's SignedVelocity state",
            description = "Replaces the calling proxy's last report of whether SignedVelocity is loaded on it. Without it the proxy "
                    + "cannot cancel a muted player's signed chat, so the admin panel warns while any proxy lacks it. Proxy tokens only.",
            operationId = "reportProxyCapabilities",
            tags = {"Proxy"}
    )
    @ApiResponse(responseCode = "204", description = "Report stored")
    @ApiResponse(responseCode = "400", description = "Invalid proxy id or SignedVelocity version",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProxyCapabilityDTO.Error.class)))
    @ApiResponse(responseCode = "503", description = "The shared cache is unavailable, retry later",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProxyCapabilityDTO.Error.class)))
    @Put("/")
    HttpResponse<ProxyCapabilityDTO> report(@Valid @Body ProxyCapabilityDTO.Report request);

    @Operation(
            summary = "Get the SignedVelocity state of every proxy",
            description = "Each proxy's last report. A proxy whose last report is older than the stale window is unknown. Staff panel only.",
            operationId = "getProxyCapabilities",
            tags = {"Proxy"}
    )
    @ApiResponse(responseCode = "200", description = "The last state of every proxy that has reported",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProxyCapabilityDTO.Response.class)))
    @Get("/")
    HttpResponse<ProxyCapabilityDTO.Response> get();
}
