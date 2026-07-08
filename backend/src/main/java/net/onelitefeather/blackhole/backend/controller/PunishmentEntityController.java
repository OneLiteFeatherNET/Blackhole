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
import net.onelitefeather.blackhole.backend.security.ConnectorScopes;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

@Secured({Roles.ADMIN, Roles.STAFF, Roles.SERVICE})
@Controller(value = ApiVersion.V1 + "/punishment")
public class PunishmentEntityController {

    private final PunishmentRepository punishmentRepository;
    private final PunishmentApplicationService punishmentApplicationService;

    /**
     * Create a new PunishmentEntityController with the given values.
     *
     * @param punishmentRepository        the repository to list punishments
     * @param punishmentApplicationService applies a punishment template to a profile
     */
    @Inject
    public PunishmentEntityController(
            PunishmentRepository punishmentRepository,
            PunishmentApplicationService punishmentApplicationService
    ) {
        this.punishmentRepository = punishmentRepository;
        this.punishmentApplicationService = punishmentApplicationService;
    }

    /**
     * Set an active punishment to the database.
     *
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
    @Post("/active/{owner}/{templateId}/{source}")
    public HttpResponse<PunishProfileResponse> add(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            UUID templateId,
            UUID source
    ) {
        var profile = this.punishmentApplicationService.apply(owner, templateId, source);
        if (profile.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(profile.get().toDTO());
    }

    /**
     * Revoke an active ban.
     *
     * @param owner the owner of the punishment
     * @param source the staff/system identity revoking the punishment
     * @return the updated profile
     */
    @Operation(
            summary = "Revoke active ban",
            description = "Revokes a player's active ban (SERVER or NETWORK), moving it into history",
            operationId = "revokeBan",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Ban successfully revoked",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishProfileResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found, or has no active ban"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input parameters (owner must be SHA-512 hash)"
    )
    @Validated
    @Post("/active/{owner}/ban/revoke/{source}")
    public HttpResponse<PunishProfileResponse> revokeBan(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            UUID source
    ) {
        var profile = this.punishmentApplicationService.revokeBan(owner, source);
        if (profile.isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(profile.get().toDTO());
    }

    /**
     * Revoke an active mute.
     *
     * @param owner the owner of the punishment
     * @param source the staff/system identity revoking the punishment
     * @return the updated profile
     */
    @Operation(
            summary = "Revoke active mute",
            description = "Revokes a player's active chat ban, moving it into history",
            operationId = "revokeMute",
            tags = {"Punishment"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Mute successfully revoked",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PunishProfileResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found, or has no active mute"
    )
    @ApiResponse(
            responseCode = "400",
            description = "Invalid input parameters (owner must be SHA-512 hash)"
    )
    @Validated
    @Post("/active/{owner}/mute/revoke/{source}")
    public HttpResponse<PunishProfileResponse> revokeMute(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            UUID source
    ) {
        var profile = this.punishmentApplicationService.revokeMute(owner, source);
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
    @Secured({Roles.ADMIN, Roles.STAFF, Roles.SERVICE, ConnectorScopes.PUNISHMENT_READ})
    @Get()
    public HttpResponse<Page<PunishEntryDTO>> getAll(Pageable pageable) {
        Page<PunishmentEntity> entries = this.punishmentRepository.findAll(pageable);
        return HttpResponse.ok(entries.map(PunishmentEntity::toDTO));
    }
}
