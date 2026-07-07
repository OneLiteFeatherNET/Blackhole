package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentApplicationService;
import net.onelitefeather.blackhole.backend.response.PunishProfileResponse;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.security.TenantContext;

import java.util.UUID;

@Secured({Roles.PLATFORM_ADMIN, Roles.TENANT_ADMIN, Roles.STAFF, Roles.SERVICE})
@Controller(value = "/punishment")
public class PunishmentEntityController {

    private final PunishmentRepository punishmentRepository;
    private final TenantContext tenantContext;
    private final PunishmentApplicationService punishmentApplicationService;

    /**
     * Create a new PunishmentEntityController with the given values.
     *
     * @param punishmentRepository        the repository to list punishments
     * @param tenantContext               enforces that callers only touch their own tenant's punishments
     * @param punishmentApplicationService applies a punishment template to a profile
     */
    @Inject
    public PunishmentEntityController(
            PunishmentRepository punishmentRepository,
            TenantContext tenantContext,
            PunishmentApplicationService punishmentApplicationService
    ) {
        this.punishmentRepository = punishmentRepository;
        this.tenantContext = tenantContext;
        this.punishmentApplicationService = punishmentApplicationService;
    }

    /**
     * Set an active punishment to the database.
     *
     * @param tenantId the tenant the punishment belongs to
     * @param owner the owner of the punishment
     * @param templateId the template id of the punishment
     * @param source the source of the punishment
     * @return the added punishment
     */
    @Operation(
            summary = "Add active punishment",
            description = "Creates and applies an active punishment to a player profile based on a punishment template",
            operationId = "addActivePunishment",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Punishment successfully applied to profile",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishProfileResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Profile or template not found"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input parameters (owner must be SHA-512 hash)"
    )
    @Validated
    @Post("/active/{tenantId}/{owner}/{templateId}/{source}")
    public HttpResponse<PunishProfileResponse> add(
            UUID tenantId,
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            UUID templateId,
            UUID source
    ) {
        this.tenantContext.requireTenantAccess(tenantId);
        var profile = this.punishmentApplicationService.apply(tenantId, owner, templateId, source);
        if (profile.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(profile.get().toDTO());
    }

    /**
     * Get all punishments from the database.
     *
     * @return a list with all punishments
     */
    @Operation(
            summary = "Get all punishments",
            description = "Retrieves a paginated list of all punishment entries in the system",
            operationId = "getPunishments",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved punishment entries",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(implementation = PunishEntryDTO.class),
                            arraySchema = @Schema(implementation = Page.class)
                    )
            )
    )
    @Get()
    public HttpResponse<Page<PunishEntryDTO>> getAll(Pageable pageable) {
        Page<PunishmentEntity> entries = this.tenantContext.isPlatformAdmin()
                ? this.punishmentRepository.findAll(pageable)
                : this.punishmentRepository.findByTenantId(this.tenantContext.currentTenantId().orElseThrow(), pageable);
        return HttpResponse.ok(entries.map(PunishmentEntity::toDTO));
    }
}
