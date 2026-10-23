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
 * Routing and OpenAPI annotations live on {@link AdminStaffLinkApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/admin/staff-link")
public class AdminStaffLinkController implements AdminStaffLinkApi {

    private final StaffLinkService staffLinkService;

    @Inject
    public AdminStaffLinkController(StaffLinkService staffLinkService) {
        this.staffLinkService = staffLinkService;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<StaffLinkDTO> list() {
        return StaffLinkController.respond(this.staffLinkService.list());
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<StaffLinkDTO> unlink(UUID oid, Actor actor) {
        return StaffLinkController.respond(this.staffLinkService.unlink(oid, actor));
    }
}
