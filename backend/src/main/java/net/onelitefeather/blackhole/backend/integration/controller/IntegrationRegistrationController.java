package net.onelitefeather.blackhole.backend.integration.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.audit.AuditHeaders;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.integration.IntegrationService;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.List;

/** Routing and OpenAPI annotations live on {@link IntegrationRegistrationApi}; this class only maps results to responses. */
@Validated
@Version(ApiVersion.V1)
@Controller("/integration/registrations")
public class IntegrationRegistrationController implements IntegrationRegistrationApi {

    private final IntegrationService service;

    @Inject
    public IntegrationRegistrationController(IntegrationService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<IntegrationDTO> create(IntegrationDTO.CreateRequest request, Actor actor) {
        return AuditHeaders.audited(toResponse(this.service.create(request, actor)), actor);
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<IntegrationDTO> update(long id, IntegrationDTO.UpdateRequest request, Actor actor) {
        return AuditHeaders.audited(toResponse(this.service.update(id, request, actor)), actor);
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<List<IntegrationDTO.Response>> list() {
        return HttpResponse.ok(this.service.list());
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<IntegrationDTO> get(long id) {
        return toResponse(this.service.get(id));
    }

    private static MutableHttpResponse<IntegrationDTO> toResponse(IntegrationDTO result) {
        if (result instanceof IntegrationDTO.Error error) {
            return HttpResponse.<IntegrationDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
