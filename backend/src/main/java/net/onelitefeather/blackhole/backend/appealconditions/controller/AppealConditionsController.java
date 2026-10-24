package net.onelitefeather.blackhole.backend.appealconditions.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.appealconditions.dto.AppealConditionsDTO;
import net.onelitefeather.blackhole.backend.appealconditions.service.AppealConditionsService;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.List;

/** Routing and OpenAPI annotations live on {@link AppealConditionsApi}. Reading needs {@code VIEWER}, saving {@code ADMIN}. */
@Version(ApiVersion.V1)
@Controller("/appeal-conditions")
public class AppealConditionsController implements AppealConditionsApi {

    private final AppealConditionsService service;

    @Inject
    public AppealConditionsController(AppealConditionsService service) {
        this.service = service;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<AppealConditionsDTO> current() {
        return toResponse(this.service.current());
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<List<AppealConditionsDTO.Response>> versions() {
        return HttpResponse.ok(this.service.versions());
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<AppealConditionsDTO> save(@Nullable AppealConditionsDTO.CreateRequest request, Actor actor) {
        return toResponse(this.service.save(request, actor.oid()));
    }

    private static HttpResponse<AppealConditionsDTO> toResponse(AppealConditionsDTO result) {
        if (result instanceof AppealConditionsDTO.Error error) {
            return HttpResponse.<AppealConditionsDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
