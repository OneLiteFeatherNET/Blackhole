package net.onelitefeather.blackhole.backend.proxy.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.proxy.ProxyCapabilityService;
import net.onelitefeather.blackhole.backend.proxy.dto.ProxyCapabilityDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link ProxyCapabilityApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/proxy/capabilities")
public class ProxyCapabilityController implements ProxyCapabilityApi {

    private final ProxyCapabilityService service;

    @Inject
    public ProxyCapabilityController(ProxyCapabilityService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<ProxyCapabilityDTO> report(ProxyCapabilityDTO.Report request) {
        ProxyCapabilityDTO result = this.service.report(request);
        if (result instanceof ProxyCapabilityDTO.Error error) {
            return HttpResponse.<ProxyCapabilityDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.noContent();
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<ProxyCapabilityDTO.Response> get() {
        return HttpResponse.ok(this.service.capabilities());
    }
}
