package net.onelitefeather.blackhole.velocity.auth;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;

/**
 * Posts the token request. An interface so the provider can be tested without a network.
 */
public interface TokenTransport {

    /**
     * @param uri  the token endpoint
     * @param form the {@code application/x-www-form-urlencoded} body
     * @return the answer, whatever its status
     * @throws IOException if there is no answer
     */
    @NotNull Response post(@NotNull URI uri, @NotNull String form) throws IOException;

    /**
     * @param status the HTTP status
     * @param body   the response body
     */
    record Response(int status, @NotNull String body) {
    }
}
