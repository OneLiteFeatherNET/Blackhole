package net.onelitefeather.blackhole.backend.punishment.controller;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateRequestDTO;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentTemplateService;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;

import java.util.UUID;

/**
 * A handler for punishment templates.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
@Version(ApiVersion.V1)
@Controller("/template")
public class PunishmentTemplateController {

    private final PunishmentTemplateService templateService;

    /**
     * Create a new PunishmentTemplateController
     *
     * @param templateService the service to use
     */
    @Inject
    public PunishmentTemplateController(PunishmentTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * Add a template to the database.
     *
     * @param template the template to add
     * @return the added template
     */
    @Operation(
            summary = "Create punishment template",
            description = "Creates a new punishment template.",
            operationId = "addTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully created",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishTemplateDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "405",
            description = "Method not allowed - identifier must be null for creation"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid template data"
    )
    @Post("/")
    public HttpResponse<PunishTemplateDTO> addTemplate(@Valid @Body PunishTemplateRequestDTO template) {
        return switch (this.templateService.create(template)) {
            case PunishmentTemplateService.CreateResult.Created created -> HttpResponse.ok(created.template());
            case PunishmentTemplateService.CreateResult.IdentifierNotAllowed ignored -> HttpResponse.notAllowed();
        };
    }

    /**
     * Update a template in the database.
     *
     * @param template the template to update
     * @return the updated template
     */
    @Operation(
            summary = "Update punishment template",
            description = "Updates an existing punishment template.",
            operationId = "updateTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully updated",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishTemplateDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Template not found"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid template data"
    )
    @Post(value = "/update")
    public HttpResponse<PunishTemplateDTO> updateTemplate(@Valid @Body PunishTemplateRequestDTO template) {
        return switch (this.templateService.update(template)) {
            case PunishmentTemplateService.UpdateResult.Updated updated -> HttpResponse.ok(updated.template());
            case PunishmentTemplateService.UpdateResult.MissingIdentifier ignored -> HttpResponse.badRequest();
            case PunishmentTemplateService.UpdateResult.NotFound ignored -> HttpResponse.notFound();
        };
    }

    /**
     * Remove a template from the database.
     *
     * @param identifier the identifier of the template to remove
     * @return the removed template
     */
    @Operation(
            summary = "Delete punishment template",
            description = "Deletes a punishment template by its identifier",
            operationId = "deleteTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully deleted",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishTemplateDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Template not found"
    )
    @Delete(value = "/delete/{identifier}")
    public HttpResponse<PunishTemplateDTO> removeTemplate(@PathVariable UUID identifier) {
        return this.templateService.remove(identifier)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    /**
     * Get all templates from the database.
     *
     * @return a list of all templates
     */
    @Operation(
            summary = "Get all punishment templates",
            description = "Retrieves a paginated list of all punishment templates in the system",
            operationId = "getAllTemplates",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved punishment templates",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(implementation = PunishTemplateDTO.class),
                            arraySchema = @Schema(implementation = Page.class)
                    )
            )
    )
    @Get("/")
    public HttpResponse<Page<PunishTemplateDTO>> getAll(Pageable pageable) {
        return HttpResponse.ok(this.templateService.findAll(pageable));
    }

    /**
     * Get a template by identifier from the database.
     *
     * @param identifier the identifier of the template to retrieve
     * @return the template if found
     */
    @Operation(
            summary = "Get punishment template by ID",
            description = "Retrieves a specific punishment template by its identifier",
            operationId = "getTemplateById",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully retrieved",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishTemplateDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Template not found"
    )
    @Get("/{identifier}")
    public HttpResponse<PunishTemplateDTO> get(UUID identifier) {
        return this.templateService.find(identifier)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }
}
