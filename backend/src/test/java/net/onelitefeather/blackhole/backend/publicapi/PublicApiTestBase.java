package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared steps of the public API tests: punish somebody as staff, read the Ban ID back, log in as the player and
 * call the public routes with the session token, with the headers a test needs.
 */
@org.junit.jupiter.api.Timeout(60)
abstract class PublicApiTestBase extends ApiIntegrationTest {

    protected static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    /** A response with its headers. */
    protected record Raw(int status, String body, Map<String, String> headers, JsonMapper mapper) {

        Map<String, Object> json() {
            return new Reply(this.status, this.body, this.mapper).json();
        }

        String header(String name) {
            return this.headers.get(name.toLowerCase());
        }
    }

    @Override
    protected Map<String, Object> extraProperties() {
        // Filing opens at once and a repeat after a denial waits a week, so tests need no clock of the server.
        return Map.of("blackhole.appeal.min-days-manual", 0, "blackhole.appeal.min-days-auto", 0, "test.recording-events", true);
    }

    protected RecordingEventPublisher events() {
        return (RecordingEventPublisher) this.context.getBean(net.onelitefeather.blackhole.backend.events.DomainEventPublisher.class);
    }

    protected Raw call(HttpMethod method, String path, String token, Object body, Map<String, String> headers) {
        MutableHttpRequest<?> request = HttpRequest.create(method, path);
        if (body != null) {
            request = request.body(body).contentType("application/json");
        }
        if (token != null) {
            request.bearerAuth(token);
        }
        headers.forEach(request::header);
        JsonMapper mapper = this.context.getBean(JsonMapper.class);
        try (HttpClient client = this.context.createBean(HttpClient.class, this.server.getURL())) {
            HttpResponse<String> response = client.toBlocking().exchange(request, String.class);
            return new Raw(response.code(), response.body(), lowerCased(response), mapper);
        } catch (HttpClientResponseException e) {
            return new Raw(e.getStatus().getCode(), e.getResponse().getBody(String.class).orElse(""), lowerCased(e.getResponse()), mapper);
        }
    }

    protected Raw call(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, Map.of());
    }

    private static Map<String, String> lowerCased(HttpResponse<?> response) {
        Map<String, String> headers = new HashMap<>();
        response.getHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
        return headers;
    }

    /** Punishes {@code owner} with a one day template; returns the punishment identifier. */
    protected String punish(String owner, String key) {
        assertEquals(200, post("/template", Map.of("key", key, "reason", "Spamming the chat", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10)))).status());
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", key, "source", STAFF));
        assertEquals(200, applied.status(), applied.body());
        @SuppressWarnings("unchecked") Map<String, Object> punishment = (Map<String, Object>) applied.json().get("punishment");
        return (String) punishment.get("identifier");
    }

    protected String banIdOf(String punishment) {
        return (String) get("/punishment/" + punishment + "/ban-id", this.jwt.moderator()).json().get("banId");
    }

    protected Raw login(String banId) {
        return call(HttpMethod.POST, "/public/session", null, Map.of("banId", banId));
    }

    /** Punishes, logs the player in and returns the session token. */
    protected String sessionFor(String punishment) {
        Raw login = login(banIdOf(punishment));
        assertEquals(200, login.status(), login.body());
        return (String) login.json().get("token");
    }

    protected Raw playerGet(String path, String token) {
        return call(HttpMethod.GET, path, token, null);
    }

    protected Raw playerPost(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, body);
    }

    protected Raw playerPost(String path, String token, Object body, String idempotencyKey) {
        return call(HttpMethod.POST, path, token, body, Map.of("Idempotency-Key", idempotencyKey));
    }

    protected void markLeaked(String punishment) {
        Reply marked = post("/case/" + punishment + "/ban-id/leak", Map.of("reason", "SHARED"), this.jwt.moderator());
        assertEquals(200, marked.status(), marked.body());
    }

    protected String fileAppeal(String token) {
        Raw filed = playerPost("/public/appeal", token, Map.of("statement", "I am sorry and it will not happen again."));
        assertEquals(200, filed.status(), filed.body());
        return filed.body();
    }
}
