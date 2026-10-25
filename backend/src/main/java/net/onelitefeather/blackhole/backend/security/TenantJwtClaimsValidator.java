package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

import java.util.Set;

/**
 * Refuses tokens of another Entra tenant when {@code blackhole.security.tenant-id} is set. The
 * issuer check already pins the tenant for Entra; this keeps working if an operator points the
 * issuer at a multi-tenant authority. With no tenant configured it accepts all, but only in the
 * dev and test environments (the mock server); elsewhere a missing tenant id refuses every token
 * (and {@link StartupRequirementsGuard} stops the start before that).
 */
@Singleton
public class TenantJwtClaimsValidator implements GenericJwtClaimsValidator<Object> {

    static final String TENANT_CLAIM = "tid";

    private final String tenantId;

    private final boolean tenantOptional;

    public TenantJwtClaimsValidator(SecurityProperties properties, Environment environment) {
        this.tenantId = properties.tenantId().trim();
        this.tenantOptional = environment.getActiveNames().stream()
                .anyMatch(Set.of(Environment.DEVELOPMENT, Environment.TEST)::contains);
    }

    @Override
    public boolean validate(Claims claims, Object request) {
        if (this.tenantId.isEmpty()) {
            return this.tenantOptional;
        }
        return this.tenantId.equals(String.valueOf(claims.get(TENANT_CLAIM)));
    }
}
