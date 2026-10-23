package net.onelitefeather.blackhole.backend.cloudnet.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.cloudnet.CatalogueService;
import net.onelitefeather.blackhole.backend.cloudnet.dto.CatalogueDTO;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link CatalogueApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/cloudnet/catalogue")
public class CatalogueController implements CatalogueApi {

    private final CatalogueService catalogueService;

    @Inject
    public CatalogueController(CatalogueService catalogueService) {
        this.catalogueService = catalogueService;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<CatalogueDTO> report(CatalogueDTO.ReportRequest request) {
        CatalogueDTO result = this.catalogueService.report(request);
        if (result instanceof CatalogueDTO.Error error) {
            return HttpResponse.<CatalogueDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<CatalogueDTO.Response> get() {
        return HttpResponse.ok(this.catalogueService.catalogue());
    }
}
