package net.onelitefeather.blackhole.backend.problem;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Sends one request to the embedded test server and keeps what a client sees: the status, the
 * {@code Content-Type} header and the body. {@link net.onelitefeather.blackhole.backend.ApiIntegrationTest}
 * does not expose headers, and problem responses are judged by their content type.
 */
final class RawHttp {

    /** The answer as a client receives it. */
    record Reply(int status, String contentType, String body) {
    }

    private RawHttp() {
    }

    static Reply send(URL base, String method, String path, String body, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("the request failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the request was interrupted: " + method + " " + path, e);
        }
    }
}
