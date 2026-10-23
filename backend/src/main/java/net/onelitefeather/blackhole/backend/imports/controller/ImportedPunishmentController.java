package net.onelitefeather.blackhole.backend.imports.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.imports.dto.ImportedPunishmentDTO;
import net.onelitefeather.blackhole.backend.imports.service.ImportedPunishmentService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link ImportedPunishmentApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/admin/import/punishments")
public class ImportedPunishmentController implements ImportedPunishmentApi {

    private final ImportedPunishmentService service;

    @Inject
    public ImportedPunishmentController(ImportedPunishmentService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ImportedPunishmentDTO> groups() {
        return HttpResponse.ok(this.service.groups());
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<ImportedPunishmentDTO> retemplate(ImportedPunishmentDTO.RetemplateRequest request, Actor actor) {
        ImportedPunishmentDTO result = this.service.retemplate(request, actor);
        if (result instanceof ImportedPunishmentDTO.Error error) {
            return HttpResponse.<ImportedPunishmentDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
