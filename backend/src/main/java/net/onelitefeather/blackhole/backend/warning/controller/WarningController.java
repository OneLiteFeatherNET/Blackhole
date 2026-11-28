package net.onelitefeather.blackhole.backend.warning.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.warning.dto.WarningDTO;
import net.onelitefeather.blackhole.backend.warning.service.WarningService;

/**
 * Routing and OpenAPI annotations live on {@link WarningApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/warn")
public class WarningController implements WarningApi {

    private final WarningService warningService;

    /**
     * @param warningService reads warnings and records the player's confirmation of them
     */
    @Inject
    public WarningController(WarningService warningService) {
        this.warningService = warningService;
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<WarningDTO> get(String id) {
        return answer(this.warningService.find(id));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<WarningDTO> pending(String owner, @Nullable Integer limit) {
        return answer(this.warningService.pending(owner, limit));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<WarningDTO> acknowledge(String id, WarningDTO.AcknowledgeRequest request) {
        return switch (this.warningService.acknowledge(id, request)) {
            case WarningDTO.Error error -> HttpResponse.<WarningDTO>status(error.kind().status()).body(error);
            case WarningDTO stored -> HttpResponse.noContent();
        };
    }

    private static HttpResponse<WarningDTO> answer(WarningDTO result) {
        return switch (result) {
            case WarningDTO.Error error -> HttpResponse.<WarningDTO>status(error.kind().status()).body(error);
            case WarningDTO found -> HttpResponse.ok(found);
        };
    }
}
