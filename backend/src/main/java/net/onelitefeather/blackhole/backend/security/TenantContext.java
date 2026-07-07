package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the authenticated caller's tenant from the current request's JWT claims and enforces
 * that write/read operations stay within the caller's own tenant unless they hold
 * {@link Roles#PLATFORM_ADMIN}.
 */
@Singleton
public class TenantContext {

    private static final String TENANT_ID_ATTRIBUTE = "tenantId";

    private final SecurityService securityService;

    public TenantContext(SecurityService securityService) {
        this.securityService = securityService;
    }

    /**
     * @return the tenantId claim of the currently authenticated caller, if any
     */
    public Optional<UUID> currentTenantId() {
        return this.securityService.getAuthentication()
                .map(Authentication::getAttributes)
                .map(attributes -> attributes.get(TENANT_ID_ATTRIBUTE))
                .map(Object::toString)
                .map(UUID::fromString);
    }

    /**
     * @return {@code true} if the currently authenticated caller holds the platform-wide admin role
     */
    public boolean isPlatformAdmin() {
        return this.securityService.hasRole(Roles.PLATFORM_ADMIN);
    }

    /**
     * Verifies the authenticated caller is allowed to act on the given tenant: either they hold
     * {@link Roles#PLATFORM_ADMIN} (which can act across tenants), or their own {@code tenantId}
     * claim matches the requested tenant exactly.
     *
     * @param requestedTenantId the tenant the current request is trying to act on
     * @throws TenantAccessDeniedException if the caller isn't authorized for that tenant
     */
    public void requireTenantAccess(UUID requestedTenantId) {
        if (isPlatformAdmin()) {
            return;
        }
        UUID callerTenantId = currentTenantId()
                .orElseThrow(() -> new TenantAccessDeniedException("No tenant associated with the authenticated caller"));
        if (!callerTenantId.equals(requestedTenantId)) {
            throw new TenantAccessDeniedException("Caller is not authorized for tenant " + requestedTenantId);
        }
    }
}
