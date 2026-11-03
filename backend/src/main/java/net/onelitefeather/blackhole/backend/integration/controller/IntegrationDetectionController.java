package net.onelitefeather.blackhole.backend.integration.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.integration.IntegrationDetectionService;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDetectionDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/** Routing and OpenAPI annotations live on {@link IntegrationDetectionApi}; this class only maps the result to a response. */
@Version(ApiVersion.V1)
@Controller("/integration/detections")
public class IntegrationDetectionController implements IntegrationDetectionApi {

    private final IntegrationDetectionService service;

    @Inject
    public IntegrationDetectionController(IntegrationDetectionService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.INTEGRATION)
    public HttpResponse<IntegrationDetectionDTO> ingest(String body, Authentication authentication) {
        return toResponse(this.service.ingest(body, authentication));
    }

    private static MutableHttpResponse<IntegrationDetectionDTO> toResponse(IntegrationDetectionDTO result) {
        if (result instanceof IntegrationDetectionDTO.Error error) {
            return HttpResponse.<IntegrationDetectionDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
