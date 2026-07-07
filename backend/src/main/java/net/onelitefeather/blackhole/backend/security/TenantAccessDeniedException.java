package net.onelitefeather.blackhole.backend.security;

/**
 * Thrown when an authenticated caller tries to act on a tenant other than their own without
 * holding {@link Roles#PLATFORM_ADMIN}.
 */
public class TenantAccessDeniedException extends RuntimeException {

    public TenantAccessDeniedException(String message) {
        super(message);
    }
}
