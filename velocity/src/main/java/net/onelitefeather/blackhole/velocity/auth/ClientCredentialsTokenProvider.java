package net.onelitefeather.blackhole.velocity.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.velocity.config.AuthConfig;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gets access tokens with the OAuth2 client credentials grant and keeps the current one until shortly
 * before it expires.
 *
 * <p>One caller at a time requests a new token, and that request runs outside the lock, so a slow token
 * endpoint never holds up the other callers. A caller that arrives meanwhile waits for the request, but
 * only up to {@link #WAIT_LIMIT}; a caller whose token has not expired yet uses it at once instead.</p>
 */
public final class ClientCredentialsTokenProvider implements TokenSource {

    /** A token is replaced this long before it expires. */
    public static final Duration SKEW = Duration.ofSeconds(60);

    /**
     * How long a caller waits for a token request that another caller started. Shorter than the transport's own
     * timeout: a caller that gives up still has a valid token to use, or fails and tries again on its next call.
     */
    static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DETAIL_LIMIT = 300;

    /** A token, with the instant it is replaced at ({@code refreshAt}) and the instant it stops being valid ({@code expiresAt}). */
    private record Token(String value, Instant refreshAt, Instant expiresAt) {
    }

    private final URI tokenUri;
    private final String form;
    private final TokenTransport transport;
    private final Clock clock;
    private final Duration waitLimit;

    /** The token in use. Read without the lock, so a valid token is handed out without waiting. */
    private volatile Token token;

    /** Guards {@link #inFlight} only; it is never held while a token request runs. */
    private final Object lock = new Object();

    /** The token request in progress, or null if none; guarded by {@link #lock}. */
    private CompletableFuture<Token> inFlight;

    /**
     * @param auth      complete credentials
     * @param transport sends the token request
     * @param clock     the time source
     * @throws IllegalArgumentException if the credentials are incomplete or name no valid endpoint
     */
    public ClientCredentialsTokenProvider(@NotNull AuthConfig auth, @NotNull TokenTransport transport, @NotNull Clock clock) {
        this(auth, transport, clock, WAIT_LIMIT);
    }

    /**
     * @param auth      complete credentials
     * @param transport sends the token request
     * @param clock     the time source
     * @param waitLimit how long a caller waits for a token request that another caller started
     * @throws IllegalArgumentException if the credentials are incomplete or name no valid endpoint
     */
    public ClientCredentialsTokenProvider(@NotNull AuthConfig auth, @NotNull TokenTransport transport, @NotNull Clock clock,
                                          @NotNull Duration waitLimit) {
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
        this.waitLimit = waitLimit;
    }

    @Override
    public @NotNull String token() throws TokenException {
        Token current = this.token;
        if (isFresh(current)) {
            return current.value();
        }
        CompletableFuture<Token> request;
        boolean leader = false;
        synchronized (this.lock) {
            current = this.token;
            if (isFresh(current)) {
                return current.value();
            }
            if (this.inFlight == null) {
                request = new CompletableFuture<>();
                this.inFlight = request;
                leader = true;
            } else {
                if (current != null && this.clock.instant().isBefore(current.expiresAt())) {
                    // A refresh ahead of expiry is under way and the token still works: no reason to wait for it.
                    return current.value();
                }
                request = this.inFlight;
            }
        }
        return leader ? lead(request) : await(request, current);
    }

    @Override
    public void rejected(@NotNull String rejected) {
        // A caller that is slow to report must not throw away the newer token another caller fetched.
        synchronized (this.lock) {
            Token current = this.token;
            if (current != null && rejected.equals(current.value())) {
                this.token = null;
            }
        }
    }

    private boolean isFresh(Token candidate) {
        return candidate != null && this.clock.instant().isBefore(candidate.refreshAt());
    }

    /** Requests the token for everyone waiting on {@code request}, outside the lock. */
    private String lead(CompletableFuture<Token> request) throws TokenException {
        try {
            Token fresh = requestToken();
            synchronized (this.lock) {
                this.token = fresh;
                this.inFlight = null;
            }
            request.complete(fresh);
            return fresh.value();
        } catch (TokenException | RuntimeException | Error e) {
            // Not remembered: the next caller starts a new request.
            synchronized (this.lock) {
                this.inFlight = null;
            }
            request.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Waits, for at most {@link #waitLimit}, for the request another caller started.
     *
     * @param current the token that was in use when the wait began, possibly still valid
     */
    private String await(CompletableFuture<Token> request, Token current) throws TokenException {
        try {
            return request.get(this.waitLimit.toMillis(), TimeUnit.MILLISECONDS).value();
        } catch (TimeoutException e) {
            if (current != null && this.clock.instant().isBefore(current.expiresAt())) {
                return current.value();
            }
            throw new TokenException("Token refresh from " + this.tokenUri + " is still in progress, the call is retried later");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TokenException refused) {
                throw refused;
            }
            throw new TokenException("Token request to " + this.tokenUri + " failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException("Interrupted while waiting for a token", e);
        }
    }

    private Token requestToken() throws TokenException {
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
        Instant issued = this.clock.instant();
        return new Token(accessToken, issued.plusSeconds(expiresIn).minus(SKEW), issued.plusSeconds(expiresIn));
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
