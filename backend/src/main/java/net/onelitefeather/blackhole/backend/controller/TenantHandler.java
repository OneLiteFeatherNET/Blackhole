package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.database.entities.TenantEntity;
import net.onelitefeather.blackhole.backend.database.repository.TenantRepository;
import net.onelitefeather.blackhole.backend.dto.TenantDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.Optional;
import java.util.UUID;

/**
 * A handler for tenants. Every other domain entity (profiles, punishments, templates, ...)
 * is scoped to exactly one tenant, so tenant management is the foundational multi-tenancy
 * building block the rest of the domain model relies on. Tenant management itself is
 * cross-tenant by nature, so it's restricted to {@link Roles#PLATFORM_ADMIN}.
 */
@Secured(Roles.PLATFORM_ADMIN)
@Controller(value = "/tenant")
public class TenantHandler {

    private final TenantRepository tenantRepository;

    @Inject
    public TenantHandler(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Operation(
            summary = "Create tenant",
            description = "Creates a new tenant.",
            operationId = "addTenant",
            tags = {"Tenants"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Tenant successfully created",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TenantDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "405",
            description = "Method not allowed - identifier must be null for creation"
    )
    @ApiResponse(
            responseCode = "409",
            description = "A tenant with this slug already exists"
    )
    @Post("/")
    public HttpResponse<TenantDTO> addTenant(@Valid @Body TenantDTO tenant) {
        if (tenant.identifier() != null) {
            return HttpResponse.notAllowed();
        }
        if (this.tenantRepository.findBySlug(tenant.slug()).isPresent()) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.CONFLICT);
        }
        TenantEntity dbEntity = TenantEntity.toEntity(tenant);
        TenantEntity savedEntity = this.tenantRepository.save(dbEntity);
        return HttpResponse.ok(savedEntity.toDTO());
    }

    @Operation(
            summary = "Update tenant",
            description = "Updates an existing tenant.",
            operationId = "updateTenant",
            tags = {"Tenants"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Tenant successfully updated",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TenantDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Tenant not found"
    )
    @Post(value = "/update")
    public HttpResponse<TenantDTO> updateTenant(@Valid @Body TenantDTO tenant) {
        TenantEntity dbEntity = TenantEntity.toEntity(tenant);

        if (!this.tenantRepository.existsById(dbEntity.getIdentifier())) {
            return HttpResponse.notFound();
        }

        TenantEntity savedEntity = this.tenantRepository.update(dbEntity);
        return HttpResponse.ok(savedEntity.toDTO());
    }

    @Operation(
            summary = "Delete tenant",
            description = "Deletes a tenant by its identifier",
            operationId = "deleteTenant",
            tags = {"Tenants"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Tenant successfully deleted",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TenantDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Tenant not found"
    )
    @Delete(value = "/delete/{identifier}")
    public HttpResponse<TenantDTO> removeTenant(@PathVariable UUID identifier) {
        TenantEntity entity = this.tenantRepository.findById(identifier).orElse(null);

        if (entity == null) {
            return HttpResponse.notFound();
        }
        this.tenantRepository.delete(entity);

        return HttpResponse.ok(entity.toDTO());
    }

    @Operation(
            summary = "Get all tenants",
            description = "Retrieves a paginated list of all tenants in the system",
            operationId = "getTenants",
            tags = {"Tenants"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved tenants",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(implementation = TenantDTO.class),
                            arraySchema = @Schema(implementation = Page.class)
                    )
            )
    )
    @Get("/")
    public HttpResponse<Page<TenantDTO>> getAll(Pageable pageable) {
        Page<TenantEntity> entities = this.tenantRepository.findAll(pageable);
        return HttpResponse.ok(entities.map(TenantEntity::toDTO));
    }

    @Operation(
            summary = "Get tenant by ID",
            description = "Retrieves a specific tenant by its identifier",
            operationId = "getTenantById",
            tags = {"Tenants"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Tenant successfully retrieved",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TenantDTO.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Tenant not found"
    )
    @Get("/{identifier}")
    public HttpResponse<TenantDTO> get(UUID identifier) {
        Optional<TenantEntity> entity = this.tenantRepository.findById(identifier);
        return entity.map(TenantEntity::toDTO)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }
}
