package net.onelitefeather.blackhole.backend.punishment.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.punishment.service.PunishmentTemplateService;

import java.util.UUID;

/**
 * A handler for punishment templates. Routing and OpenAPI annotations live on
 * {@link PunishmentTemplateApi}, which this class implements.
 *
 * @author theEvilReaper
 * @version 2.0.0
 * @since 1.0.0
 */
@Validated
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
    public HttpResponse<PunishTemplateDTO> addTemplate(PunishTemplateDTO.CreateRequest template) {
        return toResponse(this.templateService.create(template));
    }

    @Override
    public HttpResponse<PunishTemplateDTO> updateTemplate(PunishTemplateDTO.UpdateRequest template) {
        return toResponse(this.templateService.update(template));
    }

    @Override
    public HttpResponse<PunishTemplateDTO> removeTemplate(UUID identifier) {
        return toResponse(this.templateService.remove(identifier));
    }

    @Override
    public HttpResponse<Page<PunishTemplateDTO.Response>> getAll(Pageable pageable) {
        return HttpResponse.ok(this.templateService.findAll(pageable));
    }

    @Override
    public HttpResponse<PunishTemplateDTO> get(UUID identifier) {
        return this.templateService.find(identifier)
                .<HttpResponse<PunishTemplateDTO>>map(HttpResponse::ok)
                .orElseGet(() -> toResponse(new PunishTemplateDTO.Error(
                        "there is no template with identifier " + identifier, net.onelitefeather.blackhole.backend.response.ErrorKind.NOT_FOUND)));
    }

    private static HttpResponse<PunishTemplateDTO> toResponse(PunishTemplateDTO result) {
        if (result instanceof PunishTemplateDTO.Error error) {
            return HttpResponse.<PunishTemplateDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
