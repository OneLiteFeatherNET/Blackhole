package net.onelitefeather.blackhole.velocity.config;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.velocity.auth.BearerHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;

/**
 * Builds the generated {@link ApiClient} for the configured backend URL.
 *
 * <p>The generated client keeps scheme, host, port and path prefix separately, so the whole URL has
 * to go through {@link ApiClient#updateBaseUri(String)}; {@code setBasePath} only sets the path
 * prefix and would produce {@code http://localhosthttp://localhost:8080}.
 */
public final class BlackholeApiClients {

    private BlackholeApiClients() {
    }

    /**
     * Creates a client whose base URI is the configured URL.
     *
     * <p>Trailing slashes are dropped because the generated operations start their paths with
     * {@code /}, which would otherwise yield {@code //sections}.
     *
     * @param baseUrl the configured backend URL, e.g. {@code http://localhost:8080} or
     *                {@code https://bans.example.net/api}
     * @return a new client pointing at that URL
     * @throws IllegalArgumentException if the URL is not an absolute http(s) URL with a host
     */
    public static @NotNull ApiClient create(@NotNull String baseUrl) {
        ApiClient client = new ApiClient();
        client.updateBaseUri(normalize(baseUrl));
        return client;
    }

    /**
     * Creates a client that sends every request with a bearer token and, if the backend rejects the
     * token, repeats the request once with a fresh one.
     *
     * @param baseUrl the configured backend URL, see {@link #create(String)}
     * @param tokens  supplies the tokens
     * @return a new client pointing at that URL
     * @throws IllegalArgumentException if the URL is not an absolute http(s) URL with a host
     */
    public static @NotNull ApiClient create(@NotNull String baseUrl, @NotNull TokenSource tokens) {
        return create(baseUrl, tokens, null);
    }

    /**
     * Like {@link #create(String, TokenSource)} over the given transport instead of a new JDK client,
     * so tests can answer requests without a network.
     *
     * @param transport the client that sends the requests, or null for a JDK client
     */
    public static @NotNull ApiClient create(@NotNull String baseUrl, @NotNull TokenSource tokens, @Nullable HttpClient transport) {
        ApiClient client = new ApiClient() {
            private HttpClient authenticated;

            @Override
            public synchronized HttpClient getHttpClient() {
                if (authenticated == null) {
                    authenticated = new BearerHttpClient(transport == null ? super.getHttpClient() : transport, tokens);
                }
                return authenticated;
            }
        };
        client.updateBaseUri(normalize(baseUrl));
        return client;
    }

    /**
     * Validates the configured URL and strips trailing slashes.
     *
     * @param baseUrl the configured backend URL
     * @return the normalized URL
     * @throws IllegalArgumentException if the URL is not an absolute http(s) URL with a host
     */
    static @NotNull String normalize(@NotNull String baseUrl) {
        String trimmed = baseUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw invalid(baseUrl, e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid(baseUrl, "the scheme must be http or https");
        }
        if (uri.getHost() == null) {
            throw invalid(baseUrl, "the host is missing");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid(baseUrl, "a query or fragment is not allowed");
        }
        String result = trimmed;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static IllegalArgumentException invalid(String baseUrl, String reason) {
        return new IllegalArgumentException(
                "Invalid baseUrl '" + baseUrl + "' in config.json: " + reason
                        + " (expected e.g. http://localhost:8080)");
    }
}
