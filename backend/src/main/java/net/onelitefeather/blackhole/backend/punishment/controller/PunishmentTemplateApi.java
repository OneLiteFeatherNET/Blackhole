package net.onelitefeather.blackhole.backend.punishment.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;

import java.util.UUID;

public interface PunishmentTemplateApi {

    @Operation(
            summary = "Create punishment template",
            description = "Creates a temporary punishment template in a section. The escalation group defaults to the key.",
            operationId = "addTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(responseCode = "200", description = "Template successfully created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid template (also when an identifier is supplied, or the section is unknown)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "A template with this key already exists",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @Post("/")
    HttpResponse<PunishTemplateDTO> addTemplate(@Valid @Body PunishTemplateDTO.CreateRequest template);

    @Operation(
            summary = "Update punishment template",
            description = "Replaces every editable field of an existing template.",
            operationId = "updateTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(responseCode = "200", description = "Template successfully updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid template (also when the identifier is missing)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Template not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "Another template already uses this key",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @Post(value = "/update")
    HttpResponse<PunishTemplateDTO> updateTemplate(@Valid @Body PunishTemplateDTO.UpdateRequest template);

    @Operation(
            summary = "Delete punishment template",
            description = "Deletes a punishment template by its identifier",
            operationId = "deleteTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(responseCode = "200", description = "Template successfully deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Template not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "At least one punishment still references this template",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @Delete(value = "/delete/{identifier}")
    HttpResponse<PunishTemplateDTO> removeTemplate(@PathVariable UUID identifier);

    @Operation(
            summary = "Get all punishment templates",
            description = "Retrieves a paginated list of all punishment templates in the system",
            operationId = "getAllTemplates",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(responseCode = "200", description = "Successfully retrieved punishment templates",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = PunishTemplateDTO.Response.class),
                            arraySchema = @Schema(implementation = Page.class))))
    @Get("/")
    HttpResponse<Page<PunishTemplateDTO.Response>> getAll(Pageable pageable);

    @Operation(
            summary = "Get punishment template by ID",
            description = "Retrieves a specific punishment template by its identifier",
            operationId = "getTemplateById",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(responseCode = "200", description = "Template successfully retrieved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Template not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.Error.class)))
    @Get("/{identifier}")
    HttpResponse<PunishTemplateDTO> get(UUID identifier);
}
