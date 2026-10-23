package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.security.StaffActorService;
import net.onelitefeather.blackhole.backend.security.dto.StaffActorDTO;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link StaffActorApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/admin/staff-actor")
public class StaffActorController implements StaffActorApi {

    private final StaffActorService staffActorService;

    @Inject
    public StaffActorController(StaffActorService staffActorService) {
        this.staffActorService = staffActorService;
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<StaffActorDTO> list() {
        return HttpResponse.ok(this.staffActorService.list());
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<StaffActorDTO> anonymize(UUID oid, Actor actor) {
        StaffActorDTO result = this.staffActorService.anonymize(oid, actor);
        if (result instanceof StaffActorDTO.Error error) {
            return HttpResponse.<StaffActorDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
