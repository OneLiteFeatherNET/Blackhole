package net.onelitefeather.blackhole.backend.security.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.AuthService;
import net.onelitefeather.blackhole.backend.security.dto.AuthDTO;

/**
 * Routing and OpenAPI annotations live on {@link AuthApi}, which this class implements.
 */
@Version(ApiVersion.V1)
@Controller("/auth")
public class AuthController implements AuthApi {

    private final AuthService authService;

    @Inject
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Override
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<AuthDTO> me(Authentication authentication) {
        AuthDTO result = this.authService.me(authentication);
        if (result instanceof AuthDTO.Error error) {
            return HttpResponse.<AuthDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
