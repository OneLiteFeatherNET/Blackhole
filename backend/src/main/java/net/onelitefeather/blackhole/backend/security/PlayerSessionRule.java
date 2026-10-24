package net.onelitefeather.blackhole.backend.security;

/**
 * The rule of the player routes under {@code /public}. It is not a staff {@link Roles role}:
 * {@link BlackholeRolesFinder} never grants it, only {@link PlayerSessionTokenValidator} does, for a valid
 * player session token. So a staff or proxy token never passes a route that needs a session, and a player
 * session never passes a route that needs a staff role.
 */
public final class PlayerSessionRule {

    /** The role of a valid player session, for {@code @Secured}. */
    public static final String ROLE = "PLAYER_SESSION";

    /** The prefix of a session token. A JWT never starts with it, so a staff token is not looked up as a session. */
    public static final String TOKEN_PREFIX = "bhps_";

    private PlayerSessionRule() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
