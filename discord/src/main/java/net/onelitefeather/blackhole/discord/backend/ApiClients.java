package net.onelitefeather.blackhole.discord.backend;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.discord.auth.BearerHttpClient;
import net.onelitefeather.blackhole.discord.auth.TokenSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;

/**
 * Builds the generated {@link ApiClient} for the configured backend URL, sending every request with a
 * bearer token. The generated client keeps scheme, host, port and path prefix separately, so the whole
 * URL has to go through {@link ApiClient#updateBaseUri(String)}.
 */
public final class ApiClients {

    private ApiClients() {
    }

    /**
     * @param baseUrl the backend URL, e.g. {@code http://localhost:8080}
     * @param tokens  supplies the bearer tokens
     * @return a client whose requests carry a token and are repeated once with a fresh one on a 401
     * @throws IllegalArgumentException if the URL is not an absolute http(s) URL with a host
     */
    public static @NotNull ApiClient create(@NotNull String baseUrl, @NotNull TokenSource tokens) {
        return create(baseUrl, tokens, null);
    }

    /**
     * Like {@link #create(String, TokenSource)} over the given transport instead of a new JDK client, so
     * tests can answer requests without a network.
     */
    public static @NotNull ApiClient create(@NotNull String baseUrl, @NotNull TokenSource tokens, @Nullable HttpClient transport) {
        ApiClient client = new ApiClient() {
            private HttpClient authenticated;

            @Override
            public synchronized HttpClient getHttpClient() {
                if (this.authenticated == null) {
                    this.authenticated = new BearerHttpClient(transport == null ? super.getHttpClient() : transport, tokens);
                }
                return this.authenticated;
            }
        };
        client.updateBaseUri(normalize(baseUrl));
        return client;
    }

    static @NotNull String normalize(@NotNull String baseUrl) {
        String trimmed = baseUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid backend URL '" + baseUrl + "': " + e.getMessage(), e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid backend URL '" + baseUrl + "': expected an absolute http(s) URL");
        }
        String result = trimmed;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
