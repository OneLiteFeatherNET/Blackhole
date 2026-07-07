package net.onelitefeather.blackhole.backend.security;

/**
 * The role scopes recognized by Blackhole. All roles except {@link #PLATFORM_ADMIN} are
 * implicitly scoped to exactly one tenant, carried as a {@code tenantId} claim/attribute on
 * the authenticated principal.
 */
public final class Roles {

    /** Cross-tenant: manages tenants themselves and can issue tokens for any tenant. */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Manages a single tenant, including issuing tokens scoped to that tenant. */
    public static final String TENANT_ADMIN = "TENANT_ADMIN";

    /** A staff member allowed to apply/manage punishments within their tenant. */
    public static final String STAFF = "STAFF";

    /** Reserved for future player-facing endpoints (e.g. appeal submission). */
    public static final String PLAYER = "PLAYER";

    /** A trusted service (e.g. a Velocity proxy) acting on behalf of one tenant. */
    public static final String SERVICE = "SERVICE";

    private Roles() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
