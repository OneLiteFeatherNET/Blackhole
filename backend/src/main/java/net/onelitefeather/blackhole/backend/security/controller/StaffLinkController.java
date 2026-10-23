package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.security.StaffLinkService;
import net.onelitefeather.blackhole.backend.security.dto.StaffLinkDTO;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link StaffLinkApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/staff-link")
public class StaffLinkController implements StaffLinkApi {

    private final StaffLinkService staffLinkService;

    @Inject
    public StaffLinkController(StaffLinkService staffLinkService) {
        this.staffLinkService = staffLinkService;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<StaffLinkDTO> issueCode(Actor actor) {
        return respond(this.staffLinkService.issueCode(actor));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<StaffLinkDTO> status(Actor actor) {
        return respond(this.staffLinkService.status(actor));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<StaffLinkDTO> unlink(Actor actor) {
        return respond(this.staffLinkService.unlinkSelf(actor));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<StaffLinkDTO> confirm(StaffLinkDTO.CreateRequest request, Actor actor) {
        return respond(this.staffLinkService.confirm(request, actor));
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<StaffLinkDTO> byMinecraft(UUID uuid) {
        return respond(this.staffLinkService.byMinecraft(uuid));
    }

    static HttpResponse<StaffLinkDTO> respond(StaffLinkDTO result) {
        if (result instanceof StaffLinkDTO.Error error) {
            return HttpResponse.<StaffLinkDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
