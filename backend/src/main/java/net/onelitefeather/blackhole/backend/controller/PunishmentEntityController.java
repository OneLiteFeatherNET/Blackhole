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
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileId;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.response.PunishProfileResponse;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.security.TenantContext;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.phoca.metadata.Durationable;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Secured({Roles.PLATFORM_ADMIN, Roles.TENANT_ADMIN, Roles.STAFF, Roles.SERVICE})
@Controller(value = "/punishment")
public class PunishmentEntityController {

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final PunishmentTemplateRepository templateRepository;
    private final TenantContext tenantContext;

    /**
     * Create a new PunishmentEntityController with the given values.
     *
     * @param punishmentRepository the repository to save the punishments
     * @param profileRepository    the repository to save the profiles
     * @param templateRepository   the repository to save the templates
     * @param tenantContext        enforces that callers only touch their own tenant's punishments
     */
    @Inject
    public PunishmentEntityController(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            PunishmentTemplateRepository templateRepository,
            TenantContext tenantContext
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
        this.tenantContext = tenantContext;
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
        PunishmentProfileEntity profile = this.profileRepository.findById(new PunishmentProfileId(tenantId, owner)).orElse(null);

        if (profile == null) {
            return HttpResponse.notFound();
        }

        PunishmentTemplateEntity template = this.templateRepository.findById(templateId).orElse(null);

        if (template == null || !tenantId.equals(template.getTenantId())) {
            return HttpResponse.notFound();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, System.currentTimeMillis());
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());
        if (template.getMetaData().containsKey(Durationable.META_DATA_KEY_DURATION)) {
            String expirationDate = (String) template.getMetaData().get(Durationable.META_DATA_KEY_DURATION);
            Duration duration = Duration.parse(expirationDate);
            metadata.put(Expirable.META_DATA_KEY_EXPIRATION_DATE, System.currentTimeMillis() + duration.toMillis());
        }

        PunishmentEntity punishDBEntity = new PunishmentEntity(IdGenerator.generateId(), tenantId, source, template.getType(), null, template, metadata);
        PunishmentEntity savedEntity = this.punishmentRepository.save(punishDBEntity);

        if (profile.getActiveBan() != null) {
            profile.getHistory().add(profile.getActiveBan());
            profile.setActiveBan(null);
        }

        if (profile.getActiveChatBan() != null) {
            profile.getHistory().add(profile.getActiveChatBan());
            profile.setActiveChatBan(null);
        }

        switch (template.getType()) {
            case CHAT -> profile.setActiveChatBan(savedEntity);
            case SERVER, NETWORK -> profile.setActiveBan(savedEntity);
        }

        this.profileRepository.update(profile);

        return HttpResponse.ok(profile.toDTO());
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
