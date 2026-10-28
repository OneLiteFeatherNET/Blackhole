package net.onelitefeather.blackhole.discord.audit;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@link WebhookTransport} over the JDK {@link HttpClient}. */
public final class JdkWebhookTransport implements WebhookTransport {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final URI uri;

    /** @param webhookUrl a URL that {@link WebhookUrl#isValid} accepted */
    public JdkWebhookTransport(@NotNull String webhookUrl) {
        this.uri = URI.create(webhookUrl);
    }

    @Override
    public Answer post(String json) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(this.uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Answer(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while posting to the audit webhook", e);
        }
    }
}
