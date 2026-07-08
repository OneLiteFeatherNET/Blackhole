package net.onelitefeather.blackhole.backend.security;

/**
 * The role scopes recognized by Blackhole. Tenant scoping is determined entirely by the
 * {@code tenantId} URL path variable on each request, not by any claim on the authenticated
 * principal - none of these roles carry or are restricted to a particular tenant.
 */
public final class Roles {

    /** Manages tenants themselves and can issue tokens for any tenant. */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Manages tenant configuration, including issuing tokens. */
    public static final String TENANT_ADMIN = "TENANT_ADMIN";

    /** A staff member allowed to apply/manage punishments. */
    public static final String STAFF = "STAFF";

    /** Reserved for future player-facing endpoints (e.g. appeal submission). */
    public static final String PLAYER = "PLAYER";

    /** A trusted service (e.g. a Velocity proxy). */
    public static final String SERVICE = "SERVICE";

    private Roles() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
