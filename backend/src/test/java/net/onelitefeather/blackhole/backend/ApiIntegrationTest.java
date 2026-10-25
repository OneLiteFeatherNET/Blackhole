package net.onelitefeather.blackhole.backend;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base for tests that call the REST API of an application started against the real containers.
 * Every test starts its own application context; the containers (and so the database) live for the
 * whole test class, so tests must use their own keys and owners to stay independent.
 *
 * <p>Every route needs a token. The plain {@code get}/{@code post}/{@code delete} calls send an
 * ADMIN token signed by this test's own {@link TestJwtIssuer}; the overloads with a token argument
 * send that token instead, or none at all when it is {@code null}.
 */
public abstract class ApiIntegrationTest extends PostgresIntegrationTest {

    /** A response reduced to what the tests assert on. */
    public record Reply(int status, String body, JsonMapper mapper) {

        public Map<String, Object> json() {
            try {
                return this.mapper.readValue(this.body, Argument.mapOf(String.class, Object.class));
            } catch (IOException e) {
                throw new IllegalStateException("not a JSON object: " + this.body, e);
            }
        }

        public List<Map<String, Object>> jsonList() {
            try {
                return this.mapper.readValue(this.body, Argument.listOf(Argument.mapOf(String.class, Object.class)));
            } catch (IOException e) {
                throw new IllegalStateException("not a JSON array: " + this.body, e);
            }
        }
    }

    /** This test's own signing key: the application trusts it and nothing else. */
    protected final TestJwtIssuer jwt = new TestJwtIssuer(new TestClock());

    protected ApplicationContext context;
    protected EmbeddedServer server;
    private HttpClient client;
    private JsonMapper mapper;

    @BeforeEach
    void startApplication() {
        this.context = ApplicationContext.run(EmbeddedServer.class, applicationProperties()).getApplicationContext();
        this.server = this.context.getBean(EmbeddedServer.class);
        this.client = this.context.createBean(HttpClient.class, this.server.getURL());
        this.mapper = this.context.getBean(JsonMapper.class);
    }

    /** Per-test property overrides on top of the container wiring; none by default. */
    protected Map<String, Object> extraProperties() {
        return Map.of();
    }

    private Map<String, Object> applicationProperties() {
        Map<String, Object> properties = new java.util.HashMap<>(contextProperties());
        // A random free port, so the tests do not depend on 8080 being free on the machine.
        properties.put("micronaut.server.port", -1);
        properties.putAll(this.jwt.contextProperties());
        properties.putAll(extraProperties());
        return properties;
    }

    @AfterEach
    void stopApplication() {
        this.client.close();
        this.context.close();
    }

    protected Reply get(String path) {
        return get(path, this.jwt.admin());
    }

    protected Reply get(String path, String token) {
        return call(HttpRequest.GET(path), token);
    }

    protected Reply post(String path, Object body) {
        return post(path, body, this.jwt.admin());
    }

    protected Reply post(String path, Object body, String token) {
        return call(body == null ? HttpRequest.POST(path, "") : HttpRequest.POST(path, body), token);
    }

    /** A POST with extra request headers, such as {@code Idempotency-Key}. */
    protected Reply post(String path, Object body, String token, Map<String, String> headers) {
        MutableHttpRequest<?> request = body == null ? HttpRequest.POST(path, "") : HttpRequest.POST(path, body);
        headers.forEach(request::header);
        return call(request, token);
    }

    protected Reply put(String path, Object body, String token) {
        return call(HttpRequest.PUT(path, body), token);
    }

    /**
     * Gives the staff member of {@code token} a display name through the API (a staff reply to a player needs one), and
     * returns the token. Each call saves a fresh, unique name, so tests stay independent.
     */
    protected String named(String token) {
        String name = "Person " + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Reply saved = put("/staff-profile/me", Map.of("displayName", name, "noticeAcknowledged", true), token);
        assertEquals(200, saved.status(), "the test staff member gets a display name: " + saved.body());
        return token;
    }

    protected Reply patch(String path, Object body, String token) {
        return call(HttpRequest.PATCH(path, body), token);
    }

    protected Reply delete(String path, String token) {
        return call(HttpRequest.DELETE(path), token);
    }

    private Reply call(MutableHttpRequest<?> request, String token) {
        try {
            if (token != null) {
                request.bearerAuth(token);
            }
            HttpResponse<String> response = this.client.toBlocking().exchange(request, String.class);
            return new Reply(response.code(), response.body(), this.mapper);
        } catch (HttpClientResponseException e) {
            String body = e.getResponse().getBody(String.class).orElse("");
            return new Reply(e.getStatus().getCode(), body, this.mapper);
        }
    }
}
