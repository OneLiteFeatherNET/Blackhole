package net.onelitefeather.blackhole.velocity.auth;

import org.jetbrains.annotations.NotNull;

/**
 * Supplies the bearer token for backend calls.
 */
public interface TokenSource {

    /**
     * @return a token that is valid now
     * @throws TokenException if none can be obtained
     */
    @NotNull String token() throws TokenException;

    /**
     * Tells the source the backend refused this token, so the next {@link #token()} must not return it.
     */
    void rejected(@NotNull String token);

    /**
     * @param reason why there is no token
     * @return a source that never has one, for a proxy without usable credentials
     */
    static @NotNull TokenSource unavailable(@NotNull String reason) {
        return new TokenSource() {
            @Override
            public @NotNull String token() throws TokenException {
                throw new TokenException(reason);
            }

            @Override
            public void rejected(@NotNull String token) {
                // nothing is ever handed out
            }
        };
    }
}
