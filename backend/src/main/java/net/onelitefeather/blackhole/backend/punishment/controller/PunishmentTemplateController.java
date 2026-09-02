package net.onelitefeather.blackhole.backend.punishment.controller;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateRequestDTO;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentTemplateService;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.core.version.annotation.Version;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;

import java.util.UUID;

/**
 * A handler for punishment templates. Routing and OpenAPI annotations live on
 * {@link PunishmentTemplateApi}, which this class implements.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
@Version(ApiVersion.V1)
@Controller("/template")
public class PunishmentTemplateController implements PunishmentTemplateApi {

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

    @Override
    public HttpResponse<PunishTemplateDTO> addTemplate(PunishTemplateRequestDTO template) {
        return switch (this.templateService.create(template)) {
            case PunishmentTemplateService.CreateResult.Created created -> HttpResponse.ok(created.template());
            case PunishmentTemplateService.CreateResult.IdentifierNotAllowed ignored -> HttpResponse.notAllowed();
        };
    }

    @Override
    public HttpResponse<PunishTemplateDTO> updateTemplate(PunishTemplateRequestDTO template) {
        return switch (this.templateService.update(template)) {
            case PunishmentTemplateService.UpdateResult.Updated updated -> HttpResponse.ok(updated.template());
            case PunishmentTemplateService.UpdateResult.MissingIdentifier ignored -> HttpResponse.badRequest();
            case PunishmentTemplateService.UpdateResult.NotFound ignored -> HttpResponse.notFound();
        };
    }

    @Override
    public HttpResponse<?> removeTemplate(UUID identifier) {
        return switch (this.templateService.remove(identifier)) {
            case PunishmentTemplateService.RemoveResult.Removed removed -> HttpResponse.ok(removed.template());
            case PunishmentTemplateService.RemoveResult.NotFound ignored -> HttpResponse.notFound();
            case PunishmentTemplateService.RemoveResult.InUse ignored ->
                    HttpResponse.status(HttpStatus.CONFLICT, "At least one punishment still references this template");
        };
    }

    @Override
    public HttpResponse<Page<PunishTemplateDTO>> getAll(Pageable pageable) {
        return HttpResponse.ok(this.templateService.findAll(pageable));
    }

    @Override
    public HttpResponse<PunishTemplateDTO> get(UUID identifier) {
        return this.templateService.find(identifier)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }
}
