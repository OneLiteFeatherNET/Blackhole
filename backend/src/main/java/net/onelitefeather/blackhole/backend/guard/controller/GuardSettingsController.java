package net.onelitefeather.blackhole.backend.guard.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.audit.AuditHeaders;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.guard.GuardSettingsService;
import net.onelitefeather.blackhole.backend.guard.dto.GuardSettingsDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/** Routing and OpenAPI annotations live on {@link GuardSettingsApi}, which this class implements. */
@Validated
@Version(ApiVersion.V1)
@Controller("/guard")
public class GuardSettingsController implements GuardSettingsApi {

    private final GuardSettingsService settings;

    @Inject
    public GuardSettingsController(GuardSettingsService settings) {
        this.settings = settings;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<GuardSettingsDTO> settings() {
        GuardSettingsDTO result = this.settings.read();
        if (result instanceof GuardSettingsDTO.Error error) {
            return HttpResponse.<GuardSettingsDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<GuardSettingsDTO> update(GuardSettingsDTO.UpdateRequest request, Actor actor) {
        GuardSettingsDTO result = this.settings.replace(request, actor);
        if (result instanceof GuardSettingsDTO.Error error) {
            return AuditHeaders.audited(HttpResponse.<GuardSettingsDTO>status(error.kind().status()).body(error), actor);
        }
        return AuditHeaders.audited(HttpResponse.ok(result), actor);
    }
}
