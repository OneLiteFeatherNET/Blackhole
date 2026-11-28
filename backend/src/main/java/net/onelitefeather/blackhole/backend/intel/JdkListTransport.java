package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The production transport: the JDK HTTP client, no redirects, a 10 s connect timeout and a 60 s request timeout. */
@Singleton
public class JdkListTransport implements ListTransport {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public Reply get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(READ_TIMEOUT).GET().build();
        try {
            HttpResponse<InputStream> response = this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Reply(response.statusCode(), response.headers().firstValue("Location").orElse(null), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the list download was interrupted", e);
        }
    }
}
