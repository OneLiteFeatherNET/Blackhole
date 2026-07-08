package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.entities.TenantEloSettingsEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.database.repository.TenantEloSettingsRepository;
import net.onelitefeather.blackhole.backend.dto.PunishType;
import net.onelitefeather.blackhole.backend.dto.TenantEloSettingsDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.Optional;
import java.util.UUID;

/**
 * A tenant's raw ELO setting overrides - base Elo, perma-ban threshold, and perma-ban template
 * per track, plus the report reward. Distinct from {@link TenantHandler} because it's editable
 * by {@link Roles#TENANT_ADMIN}, not just {@link Roles#PLATFORM_ADMIN} - a tenant should be able
 * to fine-tune its own thresholds without platform-operator involvement.
 */
@Secured({Roles.PLATFORM_ADMIN, Roles.TENANT_ADMIN})
@Controller(ApiVersion.V1 + "/tenant")
public class TenantEloSettingsController {

    private final TenantEloSettingsRepository settingsRepository;
    private final PunishmentTemplateRepository templateRepository;

    @Inject
    public TenantEloSettingsController(
            TenantEloSettingsRepository settingsRepository,
            PunishmentTemplateRepository templateRepository
    ) {
        this.settingsRepository = settingsRepository;
        this.templateRepository = templateRepository;
    }

    @Operation(
            summary = "Get tenant ELO settings",
            description = "Retrieves a tenant's raw ELO setting overrides. Unset fields (null) inherit the platform default; always returns 200, synthesizing an all-null DTO if the tenant hasn't customized anything yet.",
            operationId = "getTenantEloSettings",
            tags = {"Elo"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Current ELO settings for the tenant",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TenantEloSettingsDTO.class))
    )
    @Get("/{tenantId}/elo-settings")
    public HttpResponse<TenantEloSettingsDTO> get(UUID tenantId) {
        return HttpResponse.ok(this.settingsRepository.findById(tenantId)
                .map(TenantEloSettingsEntity::toDTO)
                .orElseGet(() -> new TenantEloSettingsDTO(tenantId, null, null, null, null, null, null, null)));
    }

    @Operation(
            summary = "Update tenant ELO settings",
            description = "Creates or updates a tenant's ELO setting overrides.",
            operationId = "updateTenantEloSettings",
            tags = {"Elo"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "ELO settings successfully saved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TenantEloSettingsDTO.class))
    )
    @ApiResponse(responseCode = "400", description = "tenantId mismatch, or a configured perma-ban template doesn't belong to this tenant / doesn't match the expected type for its track")
    @Validated
    @Post("/{tenantId}/elo-settings")
    public HttpResponse<?> update(UUID tenantId, @Body @Valid TenantEloSettingsDTO settings) {
        if (!tenantId.equals(settings.tenantId())) {
            return HttpResponse.badRequest("tenantId in path and body must match");
        }

        String templateError = validatePermaBanTemplate(tenantId, settings.permaBanTemplateChatId(), PunishType.CHAT)
                .or(() -> validatePermaBanTemplate(tenantId, settings.permaBanTemplateGameplayId(), PunishType.NETWORK))
                .orElse(null);
        if (templateError != null) {
            return HttpResponse.badRequest(templateError);
        }

        TenantEloSettingsEntity entity = TenantEloSettingsEntity.toEntity(settings);
        TenantEloSettingsEntity saved = this.settingsRepository.existsById(tenantId)
                ? this.settingsRepository.update(entity)
                : this.settingsRepository.save(entity);
        return HttpResponse.ok(saved.toDTO());
    }

    private Optional<String> validatePermaBanTemplate(UUID tenantId, UUID templateId, PunishType expectedType) {
        if (templateId == null) {
            return Optional.empty();
        }
        PunishmentTemplateEntity template = this.templateRepository.findById(templateId).orElse(null);
        if (template == null || !tenantId.equals(template.getTenantId())) {
            return Optional.of("permaBanTemplate " + templateId + " does not belong to tenant " + tenantId);
        }
        if (template.getType() != expectedType) {
            return Optional.of("permaBanTemplate " + templateId + " must be of type " + expectedType);
        }
        return Optional.empty();
    }
}
