package net.onelitefeather.blackhole.discord.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Gets access tokens with the OAuth2 client credentials grant and keeps the current one until shortly
 * before it expires. Callers that arrive while a token is being requested wait for that request instead
 * of sending their own.
 */
public final class ClientCredentialsTokenProvider implements TokenSource {

    /** A token is replaced this long before it expires. */
    public static final Duration SKEW = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DETAIL_LIMIT = 300;

    private final URI tokenUri;
    private final String form;
    private final TokenTransport transport;
    private final Clock clock;

    private String token;
    private Instant refreshAt = Instant.MIN;

    /**
     * @param auth      complete credentials
     * @param transport sends the token request
     * @param clock     the time source
     * @throws IllegalArgumentException if the credentials are incomplete or name no valid endpoint
     */
    public ClientCredentialsTokenProvider(@NotNull TokenSettings auth, @NotNull TokenTransport transport, @NotNull Clock clock) {
        if (!auth.isComplete()) {
            throw new IllegalArgumentException("Incomplete credentials, missing: " + auth.missing());
        }
        this.tokenUri = auth.tokenUri();
        this.form = "grant_type=client_credentials"
                + "&client_id=" + encode(auth.getClientId())
                + "&client_secret=" + encode(auth.getClientSecret())
                + "&scope=" + encode(auth.getScope());
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public synchronized @NotNull String token() throws TokenException {
        if (this.token != null && this.clock.instant().isBefore(this.refreshAt)) {
            return this.token;
        }
        return refresh();
    }

    @Override
    public synchronized void rejected(@NotNull String rejected) {
        // A caller that is slow to report must not throw away the newer token another caller fetched.
        if (rejected.equals(this.token)) {
            this.token = null;
            this.refreshAt = Instant.MIN;
        }
    }

    private String refresh() throws TokenException {
        TokenTransport.Response response;
        try {
            response = this.transport.post(this.tokenUri, this.form);
        } catch (IOException e) {
            throw new TokenException("Token request to " + this.tokenUri + " failed: " + e.getMessage(), e);
        }
        if (response.status() / 100 != 2) {
            throw new TokenException("Token request to " + this.tokenUri + " was refused with HTTP "
                    + response.status() + errorDetail(response.body()));
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new TokenException("Token response from " + this.tokenUri + " is not JSON", e);
        }
        String accessToken = body == null ? "" : body.path("access_token").asText("");
        long expiresIn = body == null ? 0 : body.path("expires_in").asLong(0);
        if (accessToken.isEmpty() || expiresIn <= 0) {
            throw new TokenException("Token response from " + this.tokenUri + " has no access_token or expires_in");
        }
        this.token = accessToken;
        this.refreshAt = this.clock.instant().plusSeconds(expiresIn).minus(SKEW);
        return accessToken;
    }

    private static String errorDetail(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            String error = node == null ? "" : node.path("error").asText("");
            String description = node == null ? "" : node.path("error_description").asText("");
            String detail = (error + " " + description).strip();
            if (detail.isEmpty()) {
                return "";
            }
            return ": " + (detail.length() > DETAIL_LIMIT ? detail.substring(0, DETAIL_LIMIT) + "..." : detail);
        } catch (IOException e) {
            return "";
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
