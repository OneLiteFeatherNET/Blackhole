package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.ipban.IpBanChanges;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both feed routes over HTTP against the real stack. Bans are written through the API. A database failure is simulated by
 * renaming the ban table for the duration of one test, which makes the real store fail.
 */
@Tag("integration")
class FirewallFeedApiIntegrationTest extends ApiIntegrationTest {

    private static final String CLIENT = "edge";
    private static final String TOKEN = "feed-token-under-test";

    /** The answer as the firewall sees it: status, body and headers. */
    private record Raw(int status, String body, HttpHeaders headers) {
    }

    @Override
    protected Map<String, Object> extraProperties() {
        try {
            Path secrets = Files.createTempDirectory("firewall-feed-secrets");
            Files.writeString(secrets.resolve(CLIENT), HexFormat.of().formatHex(FeedTokenRegistry.sha256(TOKEN)));
            return Map.of(
                    "blackhole.firewall.feed.clients", CLIENT,
                    "blackhole.firewall.feed.secrets-dir", secrets.toString());
        } catch (IOException e) {
            throw new IllegalStateException("the secrets directory of the test", e);
        }
    }

    @Test
    void aValidTokenServesTheBannedAddressAsPlainTextWithAnEtag() {
        banAddress("203.0.113.7");

        Raw answer = feed("/ips", null);

        assertEquals(200, answer.status(), "a valid token gets the feed");
        assertTrue(answer.headers().get(HttpHeaders.CONTENT_TYPE).startsWith("text/plain"), "the feed is plain text");
        String etag = answer.headers().get(HttpHeaders.ETAG);
        assertNotNull(etag, "the feed carries an ETag");
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "the ETag is strong and quoted: " + etag);
        assertTrue(answer.body().lines().anyMatch("203.0.113.7"::equals), "the banned address is one bare line");
    }

    @Test
    void aMatchingIfNoneMatchAnswersNotModifiedWithoutBody() {
        banAddress("203.0.113.8");
        String etag = feed("/ips", null).headers().get(HttpHeaders.ETAG);

        Raw answer = feed("/ips", etag);

        assertEquals(304, answer.status(), "nothing changed, so the firewall gets 304");
        assertEquals("", answer.body(), "and no body");
    }

    @Test
    void anUnknownTokenAnswersAnEmptyNotFound() {
        Raw answer = raw("/firewall/feed/not-a-known-token/ips", null);

        assertEquals(404, answer.status(), "an unknown token is a 404; body was: " + answer.body());
        assertEquals("", answer.body(), "with an empty body");
    }

    @Test
    void beforeTheFirstGoodBuildTheFeedAnswersUnavailable() throws SQLException {
        hideBanTable();
        try {
            Raw answer = feed("/ips", null);

            assertEquals(503, answer.status(), "no good build yet, so the firewall keeps its own table");
            assertEquals("", answer.body(), "and no body");
        } finally {
            showBanTable();
        }
    }

    @Test
    void aFailedRebuildStillServesTheLastGoodFeed() throws SQLException {
        banAddress("203.0.113.9");
        assertEquals(200, feed("/ips", null).status(), "the first build is good");

        hideBanTable();
        try {
            this.context.getBean(IpBanChanges.class).changed();

            Raw answer = feed("/ips", null);

            assertEquals(200, answer.status(), "a failed rebuild does not turn into a 5xx or an empty feed");
            assertTrue(answer.body().lines().anyMatch("203.0.113.9"::equals), "the last good feed still holds the ban");
        } finally {
            showBanTable();
        }
    }

    private void banAddress(String address) {
        Reply created = post("/ipban", Map.of("address", address, "reason", "feed test"));
        assertEquals(201, created.status(), "the ban is written: " + created.body());
    }

    private Raw feed(String feed, String ifNoneMatch) {
        return raw("/firewall/feed/" + TOKEN + feed, ifNoneMatch);
    }

    private Raw raw(String path, String ifNoneMatch) {
        try (HttpClient http = this.context.createBean(HttpClient.class, this.server.getURL())) {
            MutableHttpRequest<?> request = HttpRequest.GET(path);
            if (ifNoneMatch != null) {
                request.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
            }
            try {
                HttpResponse<String> response = http.toBlocking().exchange(request, String.class);
                String body = response.getBody(String.class).orElse("");
                return new Raw(response.code(), body, response.getHeaders());
            } catch (HttpClientResponseException e) {
                String body = e.getResponse().getBody(String.class).orElse("");
                return new Raw(e.getStatus().getCode(), body, e.getResponse().getHeaders());
            }
        }
    }

    /** Makes the real store fail: the ban table is renamed away for the duration of the test. */
    private void hideBanTable() throws SQLException {
        execute("ALTER TABLE ip_bans RENAME TO ip_bans_hidden");
    }

    private void showBanTable() throws SQLException {
        execute("ALTER TABLE ip_bans_hidden RENAME TO ip_bans");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
