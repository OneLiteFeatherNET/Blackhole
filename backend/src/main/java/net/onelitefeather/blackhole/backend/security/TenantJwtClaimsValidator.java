package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Singleton;

/**
 * Refuses tokens of another Entra tenant when {@code blackhole.security.tenant-id} is set. The
 * issuer check already pins the tenant for Entra; this keeps working if an operator points the
 * issuer at a multi-tenant authority. With no tenant configured (the mock server) it accepts all.
 */
@Singleton
public class TenantJwtClaimsValidator implements GenericJwtClaimsValidator<Object> {

    static final String TENANT_CLAIM = "tid";

    private final String tenantId;

    public TenantJwtClaimsValidator(SecurityProperties properties) {
        this.tenantId = properties.tenantId().trim();
    }

    @Override
    public boolean validate(Claims claims, Object request) {
        return this.tenantId.isEmpty() || this.tenantId.equals(String.valueOf(claims.get(TENANT_CLAIM)));
    }
}
