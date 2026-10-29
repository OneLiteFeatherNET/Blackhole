package net.onelitefeather.blackhole.backend.intel.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.intel.IntelLoginCheckService;
import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

/**
 * Routing and OpenAPI annotations live on {@link IntelLoginCheckApi}, which this class implements.
 */
@Validated
@Version(ApiVersion.V1)
@Controller("/intel")
public class IntelLoginCheckController implements IntelLoginCheckApi {

    private final IntelLoginCheckService loginCheck;

    @Inject
    public IntelLoginCheckController(IntelLoginCheckService loginCheck) {
        this.loginCheck = loginCheck;
    }

    @Override
    @Secured(Roles.PROXY)
    public HttpResponse<LoginCheckDTO> check(LoginCheckDTO.CheckRequest request) {
        LoginCheckDTO result = this.loginCheck.check(request);
        if (result instanceof LoginCheckDTO.Error error) {
            return HttpResponse.<LoginCheckDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
