package net.onelitefeather.blackhole.backend.areaban.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.areaban.AreaBanService;
import net.onelitefeather.blackhole.backend.areaban.dto.AreaBanDTO;
import net.onelitefeather.blackhole.backend.audit.AuditHeaders;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link AreaBanApi}, which this class implements. The rules about who may ban
 * what are in {@link AreaBanService}; this class only passes the caller's oid and admin flag through.
 */
@Validated
@Version(ApiVersion.V1)
@Controller
public class AreaBanController implements AreaBanApi {

    private final AreaBanService areaBans;

    @Inject
    public AreaBanController(AreaBanService areaBans) {
        this.areaBans = areaBans;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<AreaBanDTO> list(boolean includeInactive) {
        return HttpResponse.ok(this.areaBans.list(includeInactive));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<AreaBanDTO> create(AreaBanDTO.CreateRequest request, Actor actor) {
        AreaBanDTO result = this.areaBans.create(request, service(actor));
        if (result instanceof AreaBanDTO.Error error) {
            return AuditHeaders.audited(HttpResponse.<AreaBanDTO>status(error.kind().status()).body(error), actor);
        }
        return AuditHeaders.audited(HttpResponse.ok(result), actor);
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<AreaBanDTO> lift(String id, AreaBanDTO.LiftRequest request, Actor actor) {
        AreaBanDTO result = this.areaBans.lift(id, request, service(actor));
        if (result instanceof AreaBanDTO.Error error) {
            return AuditHeaders.audited(HttpResponse.<AreaBanDTO>status(error.kind().status()).body(error), actor);
        }
        return AuditHeaders.audited(HttpResponse.ok(result), actor);
    }

    private static AreaBanService.Actor service(Actor actor) {
        return new AreaBanService.Actor(actor.oid().toString(), actor.admin());
    }
}
