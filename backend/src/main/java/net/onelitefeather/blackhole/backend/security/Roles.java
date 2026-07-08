package net.onelitefeather.blackhole.backend.security;

/**
 * The role scopes recognized by Blackhole - a single-network backend, so there's no tenant
 * scoping to speak of; a role's JWT is simply valid or not.
 */
public final class Roles {

    /** Manages the network's configuration and can issue tokens for any other role. */
    public static final String ADMIN = "ADMIN";

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
