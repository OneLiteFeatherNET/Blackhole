package net.onelitefeather.blackhole.backend.staffprofile.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.staffprofile.StaffProfileService;
import net.onelitefeather.blackhole.backend.staffprofile.dto.StaffProfileDTO;

/**
 * Routing and OpenAPI annotations live on {@link StaffProfileApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/staff-profile")
public class StaffProfileController implements StaffProfileApi {

    private final StaffProfileService profiles;

    @Inject
    public StaffProfileController(StaffProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<StaffProfileDTO> get(Actor actor) {
        return respond(this.profiles.get(actor));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<StaffProfileDTO> save(StaffProfileDTO.UpdateRequest request, Actor actor) {
        return respond(this.profiles.save(request, actor));
    }

    static HttpResponse<StaffProfileDTO> respond(StaffProfileDTO result) {
        if (result instanceof StaffProfileDTO.Error error) {
            return HttpResponse.<StaffProfileDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
