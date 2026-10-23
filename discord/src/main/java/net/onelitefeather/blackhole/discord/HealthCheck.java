package net.onelitefeather.blackhole.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The container's health probe: asks the local health endpoint and exits 0 when the bot is connected.
 * It lives in the jar so the image needs no curl or wget.
 */
public final class HealthCheck {

    private HealthCheck() {
    }

    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("DISCORD_HEALTH_PORT", "8082");
        if (port.equals("0")) {
            return; // the endpoint is off; nothing to probe
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        System.exit(response.statusCode() == 200 ? 0 : 1);
    }
}
