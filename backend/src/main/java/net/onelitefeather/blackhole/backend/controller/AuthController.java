package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.jwt.generator.JwtTokenGenerator;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.dto.BootstrapRequestDTO;
import net.onelitefeather.blackhole.backend.dto.IssueTokenRequestDTO;
import net.onelitefeather.blackhole.backend.dto.TokenResponseDTO;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Issues the JWTs every other endpoint requires. There is no login/password flow here — Blackhole
 * has no human user accounts, only trusted callers acting on the network's behalf. Bootstrap
 * gets you the very first {@link Roles#ADMIN} token from a shared secret; every further token is
 * minted by an already-authenticated admin.
 */
@Version(ApiVersion.V1)
@Controller("/auth")
public class AuthController {

    private final JwtTokenGenerator tokenGenerator;
    private final String bootstrapSecret;

    @Inject
    public AuthController(
            JwtTokenGenerator tokenGenerator,
            @Value("${blackhole.auth.bootstrap-secret:}") String bootstrapSecret
    ) {
        this.tokenGenerator = tokenGenerator;
        this.bootstrapSecret = bootstrapSecret;
    }

    /**
     * Exchanges the operator-configured bootstrap secret (env var {@code BLACKHOLE_AUTH_BOOTSTRAP_SECRET})
     * for an {@link Roles#ADMIN} token. Permanently disabled (always 401) if that secret
     * isn't configured — there is deliberately no insecure default.
     */
    @Operation(
            summary = "Bootstrap the first admin token",
            description = "Exchanges the deployment's bootstrap secret for an ADMIN token.",
            operationId = "bootstrap",
            tags = {"Auth"}
    )
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Post("/bootstrap")
    public HttpResponse<?> bootstrap(@Valid @Body BootstrapRequestDTO request) {
        if (this.bootstrapSecret.isBlank() || !this.bootstrapSecret.equals(request.secret())) {
            return HttpResponse.unauthorized();
        }
        Authentication authentication = Authentication.build("admin", List.of(Roles.ADMIN), Map.of());
        return issue(authentication);
    }

    /**
     * Mints a token for a role other than {@link Roles#ADMIN}... except that an {@link Roles#ADMIN}
     * caller may also mint further {@code ADMIN} tokens - a natural consequence of there being a
     * single admin role now rather than a platform/tenant split.
     */
    @Operation(
            summary = "Issue a token",
            description = "Issues a token for a given role. Requires an authenticated ADMIN caller.",
            operationId = "issueToken",
            tags = {"Auth"}
    )
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Post("/token")
    public HttpResponse<?> issueToken(@Valid @Body IssueTokenRequestDTO request, Authentication caller) {
        boolean callerIsAdmin = caller.getRoles().contains(Roles.ADMIN);

        if (!callerIsAdmin) {
            return HttpResponse.status(HttpStatus.FORBIDDEN, "Only ADMIN callers may issue tokens");
        }

        if (!Set.of(Roles.ADMIN, Roles.STAFF, Roles.PLAYER, Roles.SERVICE).contains(request.role())) {
            return HttpResponse.badRequest("Cannot issue a token with role " + request.role() + " via this endpoint");
        }

        Authentication authentication = Authentication.build(request.role(), List.of(request.role()), Map.of());
        return issue(authentication);
    }

    private HttpResponse<?> issue(Authentication authentication) {
        Optional<String> token = this.tokenGenerator.generateToken(authentication, null);
        if (token.isEmpty()) {
            return HttpResponse.serverError("Failed to generate token");
        }
        return HttpResponse.ok(new TokenResponseDTO(token.get()));
    }
}
