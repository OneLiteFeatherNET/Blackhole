package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.jwt.generator.JwtTokenGenerator;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.database.repository.TenantRepository;
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
 * has no human user accounts, only tenants and trusted callers acting on their behalf. Bootstrap
 * gets you the very first {@link Roles#PLATFORM_ADMIN} token from a shared secret; every further
 * token is minted by an already-authenticated admin.
 */
@Controller("/auth")
public class AuthController {

    private final JwtTokenGenerator tokenGenerator;
    private final TenantRepository tenantRepository;
    private final String bootstrapSecret;

    @Inject
    public AuthController(
            JwtTokenGenerator tokenGenerator,
            TenantRepository tenantRepository,
            @Value("${blackhole.auth.bootstrap-secret:}") String bootstrapSecret
    ) {
        this.tokenGenerator = tokenGenerator;
        this.tenantRepository = tenantRepository;
        this.bootstrapSecret = bootstrapSecret;
    }

    /**
     * Exchanges the operator-configured bootstrap secret (env var {@code BLACKHOLE_AUTH_BOOTSTRAP_SECRET})
     * for a {@link Roles#PLATFORM_ADMIN} token. Permanently disabled (always 401) if that secret
     * isn't configured — there is deliberately no insecure default.
     */
    @Operation(
            summary = "Bootstrap the first admin token",
            description = "Exchanges the deployment's bootstrap secret for a PLATFORM_ADMIN token.",
            operationId = "bootstrap",
            tags = {"Auth"}
    )
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Post("/bootstrap")
    public HttpResponse<?> bootstrap(@Valid @Body BootstrapRequestDTO request) {
        if (this.bootstrapSecret.isBlank() || !this.bootstrapSecret.equals(request.secret())) {
            return HttpResponse.unauthorized();
        }
        Authentication authentication = Authentication.build("platform-admin", List.of(Roles.PLATFORM_ADMIN), Map.of());
        return issue(authentication);
    }

    /**
     * Mints a token scoped to a tenant. Callable by {@link Roles#PLATFORM_ADMIN} (any tenant) or
     * {@link Roles#TENANT_ADMIN} (their own tenant only). Cannot itself mint another
     * {@link Roles#PLATFORM_ADMIN} token.
     */
    @Operation(
            summary = "Issue a tenant-scoped token",
            description = "Issues a token for a given tenant and role. Requires an authenticated PLATFORM_ADMIN or TENANT_ADMIN caller.",
            operationId = "issueToken",
            tags = {"Auth"}
    )
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Post("/token")
    public HttpResponse<?> issueToken(@Valid @Body IssueTokenRequestDTO request, Authentication caller) {
        boolean callerIsPlatformAdmin = caller.getRoles().contains(Roles.PLATFORM_ADMIN);
        boolean callerIsTenantAdmin = caller.getRoles().contains(Roles.TENANT_ADMIN);

        if (!callerIsPlatformAdmin && !callerIsTenantAdmin) {
            return HttpResponse.status(HttpStatus.FORBIDDEN, "Only PLATFORM_ADMIN or TENANT_ADMIN callers may issue tokens");
        }

        if (!Set.of(Roles.TENANT_ADMIN, Roles.STAFF, Roles.PLAYER, Roles.SERVICE).contains(request.role())) {
            return HttpResponse.badRequest("Cannot issue a token with role " + request.role() + " via this endpoint");
        }

        if (!callerIsPlatformAdmin) {
            Object callerTenantId = caller.getAttributes().get("tenantId");
            if (callerTenantId == null || !callerTenantId.toString().equals(request.tenantId().toString())) {
                return HttpResponse.status(HttpStatus.FORBIDDEN, "TENANT_ADMIN may only issue tokens for their own tenant");
            }
        }

        if (!this.tenantRepository.existsById(request.tenantId())) {
            return HttpResponse.notFound();
        }

        Authentication authentication = Authentication.build(
                request.role() + ":" + request.tenantId(),
                List.of(request.role()),
                Map.of("tenantId", request.tenantId().toString())
        );
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
