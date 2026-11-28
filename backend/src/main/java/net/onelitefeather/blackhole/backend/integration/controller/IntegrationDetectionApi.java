package net.onelitefeather.blackhole.backend.integration.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDetectionDTO;

/** OpenAPI contract of the detection intake. Integrations only: the caller's registration decides what it may send. */
public interface IntegrationDetectionApi {

    @Operation(
            summary = "Submit integration detections",
            description = "Submits up to 50 detections (at most 64 KiB) from a registered integration. Each item is stored or refused on "
                    + "its own, and the answer lists one result per item in request order: ACCEPTED, DUPLICATE (already stored, nothing "
                    + "changed) or REJECTED with a reason code. The source system comes from the caller's registration, never from the body.",
            operationId = "submitIntegrationDetections",
            tags = {"Integration"},
            requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = IntegrationDetectionDTO.Request.class)))
    )
    @ApiResponse(responseCode = "200", description = "One result per item, in request order", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDetectionDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The body is not an object with one detections array", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDetectionDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "The token has no integration role, or its client has no enabled registration",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IntegrationDetectionDTO.Error.class)))
    @ApiResponse(responseCode = "413", description = "More than 50 detections or more than 64 KiB; nothing was stored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IntegrationDetectionDTO.Error.class)))
    @Post("/")
    HttpResponse<IntegrationDetectionDTO> ingest(@Body String body, @Parameter(hidden = true) Authentication authentication);
}
