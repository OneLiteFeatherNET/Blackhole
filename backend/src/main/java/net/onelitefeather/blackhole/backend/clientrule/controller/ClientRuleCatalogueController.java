package net.onelitefeather.blackhole.backend.clientrule.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleCatalogueService;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleCatalogueDTO;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link ClientRuleCatalogueApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/client-rule/catalogue")
public class ClientRuleCatalogueController implements ClientRuleCatalogueApi {

    private final ClientRuleCatalogueService service;

    @Inject
    public ClientRuleCatalogueController(ClientRuleCatalogueService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<ClientRuleCatalogueDTO> report(ClientRuleCatalogueDTO.ReportRequest request) {
        ClientRuleCatalogueDTO result = this.service.report(request);
        if (result instanceof ClientRuleCatalogueDTO.Error error) {
            return HttpResponse.<ClientRuleCatalogueDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<ClientRuleCatalogueDTO.Response> get() {
        return HttpResponse.ok(this.service.catalogue());
    }
}
