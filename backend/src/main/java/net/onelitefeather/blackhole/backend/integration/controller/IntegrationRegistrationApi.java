package net.onelitefeather.blackhole.backend.integration.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.List;

/** OpenAPI contract of the integration registry. Administrators only. */
public interface IntegrationRegistrationApi {

    @Operation(
            summary = "Register an integration",
            description = "Registers a third-party detection source and the Entra client ids allowed to report for it, with the kinds and "
                    + "the server scope. The client secret is not sent: it stays in Entra. The write is audited.",
            operationId = "createIntegrationRegistration",
            tags = {"Integration"}
    )
    @ApiResponse(responseCode = "200", description = "The stored registration", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The registration breaks a rule", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The source system or the client id is already registered",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IntegrationDTO.Error.class)))
    @Post("/")
    HttpResponse<IntegrationDTO> create(@Valid @Body IntegrationDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Update an integration registration",
            description = "Replaces the display name, the enabled flag, the kinds and the server scope. Setting enabled to false disables "
                    + "every client of the registration at once. The write is audited.",
            operationId = "updateIntegrationRegistration",
            tags = {"Integration"}
    )
    @ApiResponse(responseCode = "200", description = "The stored registration", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The registration breaks a rule", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "There is no registration with that id", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDTO.Error.class)))
    @Patch("/{id}")
    HttpResponse<IntegrationDTO> update(@PathVariable long id, @Valid @Body IntegrationDTO.UpdateRequest request,
                                        @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "List integration registrations",
            description = "Every registration, enabled or not.",
            operationId = "listIntegrationRegistrations",
            tags = {"Integration"}
    )
    @ApiResponse(responseCode = "200", description = "The registrations, ordered by source system")
    @Get("/")
    HttpResponse<List<IntegrationDTO.Response>> list();

    @Operation(
            summary = "Get an integration registration",
            operationId = "getIntegrationRegistration",
            tags = {"Integration"}
    )
    @ApiResponse(responseCode = "200", description = "The registration", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "There is no registration with that id", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = IntegrationDTO.Error.class)))
    @Get("/{id}")
    HttpResponse<IntegrationDTO> get(@PathVariable long id);
}
