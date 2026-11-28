package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The source allow-list over HTTP: the test client connects from loopback, which the allow-list does not name. */
@Tag("integration")
class FirewallFeedAllowListIntegrationTest extends ApiIntegrationTest {

    private static final String CLIENT = "edge";
    private static final String TOKEN = "feed-token-under-test";

    @Override
    protected Map<String, Object> extraProperties() {
        try {
            Path secrets = Files.createTempDirectory("firewall-feed-secrets");
            Files.writeString(secrets.resolve(CLIENT), HexFormat.of().formatHex(FeedTokenRegistry.sha256(TOKEN)));
            return Map.of(
                    "blackhole.firewall.feed.clients", CLIENT,
                    "blackhole.firewall.feed.secrets-dir", secrets.toString(),
                    "blackhole.firewall.feed.allowed-sources", "10.0.0.0/8");
        } catch (IOException e) {
            throw new IllegalStateException("the secrets directory of the test", e);
        }
    }

    @Test
    void aValidTokenFromASourceOutsideTheAllowListAnswersAnEmptyNotFound() {
        try (HttpClient http = this.context.createBean(HttpClient.class, this.server.getURL())) {
            int status;
            String body;
            try {
                HttpResponse<String> response = http.toBlocking().exchange(HttpRequest.GET("/firewall/feed/" + TOKEN + "/ips"), String.class);
                status = response.code();
                body = response.getBody(String.class).orElse("");
            } catch (HttpClientResponseException e) {
                status = e.getStatus().getCode();
                body = e.getResponse().getBody(String.class).orElse("");
            }

            assertEquals(404, status, "a source outside the allow-list gets the same 404 as an unknown token");
            assertEquals("", body, "with an empty body");
        }
    }
}
