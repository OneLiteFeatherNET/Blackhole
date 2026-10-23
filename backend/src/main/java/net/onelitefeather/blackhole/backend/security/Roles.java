package net.onelitefeather.blackhole.backend.security;

import java.util.List;

/**
 * The internal roles of the API. The constants are compile-time values so they can be used in
 * {@code @Secured}; the mapping from token claims to these roles is done by
 * {@link BlackholeRolesFinder}, never by group or role names hard-coded here.
 */
public final class Roles {

    /** Everything a moderator may do, plus configuration and erasure. Implies MODERATOR. */
    public static final String ADMIN = "ADMIN";
    /** Moderation actions and intake. Implies VIEWER. */
    public static final String MODERATOR = "MODERATOR";
    /** Read-only access. */
    public static final String VIEWER = "VIEWER";
    /** The Velocity proxy (app-only token). Implied by nothing. */
    public static final String PROXY = "PROXY";
    /** Prometheus scraping. Implied by nothing. */
    public static final String METRICS = "METRICS";

    private Roles() {
    }

    /** The roles granted by {@code role}, itself included (ADMIN > MODERATOR > VIEWER). */
    static List<String> expand(String role) {
        return switch (role) {
            case ADMIN -> List.of(ADMIN, MODERATOR, VIEWER);
            case MODERATOR -> List.of(MODERATOR, VIEWER);
            default -> List.of(role);
        };
    }
}
