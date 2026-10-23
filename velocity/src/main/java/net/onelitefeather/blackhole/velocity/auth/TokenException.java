package net.onelitefeather.blackhole.velocity.auth;

import java.io.IOException;

/**
 * No access token could be obtained. An {@link IOException} so that it travels through the HTTP client
 * as any other transport failure and reaches the commands as an {@code ApiException}.
 */
public final class TokenException extends IOException {

    public TokenException(String message) {
        super(message);
    }

    public TokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
