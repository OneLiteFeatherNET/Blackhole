package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.authentication.Authentication;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The verified player session behind a public request. It names the one punishment the session may reach;
 * a public route takes the punishment from here and never from the request.
 *
 * @param tokenHash          the SHA-256 (hex) of the session token, to find the row again; never the token
 * @param punishmentIdentifier the session's own punishment
 * @param scope              what the session may do right now ({@link PlayerScope#LIMITED} once the Ban ID is marked leaked)
 * @param csrf               the SHA-256 (hex) of the value the portal sends back on every state-changing request
 * @param expiresAt          the absolute end of the session, epoch millis
 */
public record PlayerSession(String tokenHash, String punishmentIdentifier, PlayerScope scope, String csrf, long expiresAt) {

    private static final String TOKEN_HASH = "bh.session.hash";
    private static final String PUNISHMENT = "bh.session.punishment";
    private static final String SCOPE = "bh.session.scope";
    private static final String CSRF = "bh.session.csrf";
    private static final String EXPIRES_AT = "bh.session.expires";

    /** The authentication a valid session token stands for: the one {@link PlayerSessionRule#ROLE} and nothing else. */
    public Authentication toAuthentication() {
        return Authentication.build("player-session", List.of(PlayerSessionRule.ROLE), Map.of(
                TOKEN_HASH, this.tokenHash, PUNISHMENT, this.punishmentIdentifier, SCOPE, this.scope.name(),
                CSRF, this.csrf, EXPIRES_AT, this.expiresAt));
    }

    /** The session an authentication stands for; empty for a staff or service authentication. */
    public static Optional<PlayerSession> of(Authentication authentication) {
        Map<String, Object> attributes = authentication.getAttributes();
        if (!(attributes.get(TOKEN_HASH) instanceof String hash) || !(attributes.get(PUNISHMENT) instanceof String punishment)
                || !(attributes.get(SCOPE) instanceof String scope) || !(attributes.get(CSRF) instanceof String csrf)
                || !(attributes.get(EXPIRES_AT) instanceof Number expiresAt) || !authentication.getRoles().contains(PlayerSessionRule.ROLE)) {
            return Optional.empty();
        }
        return Optional.of(new PlayerSession(hash, punishment, PlayerScope.valueOf(scope), csrf, expiresAt.longValue()));
    }

    @Override
    public String toString() {
        return "PlayerSession[scope=" + this.scope + "]";
    }
}
