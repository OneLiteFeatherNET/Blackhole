package net.onelitefeather.blackhole.velocity.auth;

import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.Closeable;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An {@link HttpClient} that sends every request with a bearer token. When the backend answers 401 it
 * gets a fresh token and repeats the request once; a second 401 is returned as it is.
 */
public final class BearerHttpClient extends HttpClient {

    private final HttpClient delegate;
    private final TokenSource tokens;

    public BearerHttpClient(@NotNull HttpClient delegate, @NotNull TokenSource tokens) {
        this.delegate = delegate;
        this.tokens = tokens;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        String token = this.tokens.token();
        HttpResponse<T> response = this.delegate.send(withBearer(request, token), handler);
        if (response.statusCode() != 401) {
            return response;
        }
        discard(response);
        this.tokens.rejected(token);
        return this.delegate.send(withBearer(request, this.tokens.token()), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return sendAsync(request, handler, null);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler
    ) {
        String token;
        try {
            token = this.tokens.token();
        } catch (TokenException e) {
            return CompletableFuture.failedFuture(e);
        }
        return this.delegate.sendAsync(withBearer(request, token), handler, pushHandler).thenCompose(response -> {
            if (response.statusCode() != 401) {
                return CompletableFuture.completedFuture(response);
            }
            discard(response);
            this.tokens.rejected(token);
            try {
                return this.delegate.sendAsync(withBearer(request, this.tokens.token()), handler, pushHandler);
            } catch (TokenException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private static HttpRequest withBearer(HttpRequest request, String token) {
        return HttpRequest.newBuilder(request, (name, value) -> !name.equalsIgnoreCase("Authorization"))
                .header("Authorization", "Bearer " + token)
                .build();
    }

    private static void discard(HttpResponse<?> response) {
        if (response.body() instanceof Closeable body) {
            try {
                body.close();
            } catch (IOException ignored) {
                // the response is dropped anyway
            }
        }
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return this.delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return this.delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return this.delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return this.delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return this.delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return this.delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return this.delegate.authenticator();
    }

    @Override
    public Version version() {
        return this.delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return this.delegate.executor();
    }
}
