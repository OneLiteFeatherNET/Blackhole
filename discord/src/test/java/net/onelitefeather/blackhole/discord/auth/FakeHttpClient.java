package net.onelitefeather.blackhole.discord.auth;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An HTTP client that answers with scripted statuses and bodies and remembers the requests, so tests
 * need no network. Each test creates its own.
 */
public final class FakeHttpClient extends HttpClient {

    private record Scripted(int status, String body) {
    }

    private final List<Scripted> script = new ArrayList<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    /** Queues an answer; once the queue is empty the last answer is repeated. */
    public FakeHttpClient answer(int status, String body) {
        this.script.add(new Scripted(status, body));
        return this;
    }

    public synchronized List<HttpRequest> requests() {
        return List.copyOf(this.requests);
    }

    /** @return the body of the n-th request, as text */
    public synchronized String bodyOf(int index) {
        return this.bodies.get(index);
    }

    @Override
    public synchronized <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        this.requests.add(request);
        this.bodies.add(read(request));
        Scripted answer = this.script.get(Math.min(this.requests.size(), this.script.size()) - 1);
        @SuppressWarnings("unchecked")
        T body = (T) new ByteArrayInputStream(answer.body().getBytes(StandardCharsets.UTF_8));
        return new Response<>(answer.status(), request, body);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return sendAsync(request, handler, null);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler
    ) {
        try {
            return CompletableFuture.completedFuture(send(request, handler));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String read(HttpRequest request) {
        return request.bodyPublisher().map(publisher -> {
            StringBuilder text = new StringBuilder();
            AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription s) {
                    subscription.set(s);
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    text.append(StandardCharsets.UTF_8.decode(item));
                }

                @Override
                public void onError(Throwable throwable) {
                    throw new IllegalStateException(throwable);
                }

                @Override
                public void onComplete() {
                }
            });
            return text.toString();
        }).orElse("");
    }

    private record Response<T>(int statusCode, HttpRequest request, T body) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return this.request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return null;
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }
}
