package net.onelitefeather.blackhole.backend.guard.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.audit.AuditHeaders;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.guard.GuardPresenceService;
import net.onelitefeather.blackhole.backend.guard.GuardService;
import net.onelitefeather.blackhole.backend.guard.dto.GuardDTO;
import net.onelitefeather.blackhole.backend.guard.dto.PresenceDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/** Routing and OpenAPI annotations live on {@link GuardApi}, which this class implements. */
@Validated
@Version(ApiVersion.V1)
@Controller("/guard")
public class GuardController implements GuardApi {

    private final GuardService guard;
    private final GuardPresenceService presence;

    @Inject
    public GuardController(GuardService guard, GuardPresenceService presence) {
        this.guard = guard;
        this.presence = presence;
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<GuardDTO> state() {
        GuardDTO result = this.guard.state();
        if (result instanceof GuardDTO.Error error) {
            return HttpResponse.<GuardDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }

    @Override
    @Secured({Roles.MODERATOR, Roles.PROXY, Roles.DISCORD})
    public HttpResponse<GuardDTO> setLevel(GuardDTO.CreateRequest request, Actor actor) {
        GuardDTO result = this.guard.setLevel(request, actor);
        if (result instanceof GuardDTO.Error error) {
            return AuditHeaders.audited(HttpResponse.<GuardDTO>status(error.kind().status()).body(error), actor);
        }
        return AuditHeaders.audited(HttpResponse.ok(result), actor);
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<PresenceDTO> presence(PresenceDTO.Heartbeat heartbeat) {
        PresenceDTO result = this.presence.report(heartbeat);
        if (result instanceof PresenceDTO.Error error) {
            return HttpResponse.<PresenceDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
