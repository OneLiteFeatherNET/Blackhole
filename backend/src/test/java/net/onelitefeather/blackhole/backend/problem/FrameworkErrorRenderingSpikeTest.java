package net.onelitefeather.blackhole.backend.problem;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.Roles;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike for the problem-details change (tasks 1.1, design D1 revisit condition). It records how the
 * framework renders the four error cases on the baseline, before {@link ProblemExceptionHandler}
 * exists. The observed values are listed in the pull request description that introduced it.
 *
 * <p>Disabled once the handler lands: from then on the same requests answer with problem bodies, so
 * these assertions describe the pre-change baseline only. Remove the annotation to replay the spike.
 */
@Disabled("Spike: pins the pre-handler baseline (results in the introducing pull request). Remove to replay.")
class FrameworkErrorRenderingSpikeTest extends ApiIntegrationTest {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("test.problem-spike", true);
    }

    @Test
    void httpStatusExceptionUnauthorizedRendersAsItDoesToday() {
        RawHttp.Reply reply = send("GET", "/test-problem-spike/unauthorized", null, this.jwt.admin());
        assertEquals(401, reply.status(), "the route throws HttpStatusException(UNAUTHORIZED): " + reply);
    }

    @Test
    void beanValidationFailureOnCasePatchRendersAsItDoesToday() {
        RawHttp.Reply reply = send("PATCH", "/case/CASE-1", "{\"expectedVersion\":\"not-a-number\"}", this.jwt.moderator());
        assertEquals(400, reply.status(), "PATCH /case/{reference} with an invalid body: " + reply);
    }

    @Test
    void protectedRouteWithoutTokenRendersAsItDoesToday() {
        RawHttp.Reply reply = send("GET", "/case", null, null);
        assertEquals(401, reply.status(), "GET /case without a token: " + reply);
    }

    @Test
    void validTokenWithoutRoleRendersAsItDoesToday() {
        RawHttp.Reply reply = send("GET", "/case", null, this.jwt.noGroup());
        assertEquals(403, reply.status(), "GET /case with a token that has no role: " + reply);
    }

    private RawHttp.Reply send(String method, String path, String body, String token) {
        return RawHttp.send(this.server.getURL(), method, path, body, token);
    }

    /** A route that throws {@code HttpStatusException(UNAUTHORIZED)}; only registered for this spike. */
    @Controller("/test-problem-spike")
    @Requires(property = "test.problem-spike", value = "true")
    static class SpikeProbeController {

        @Get("/unauthorized")
        @Secured(Roles.VIEWER)
        String unauthorized() {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "spike");
        }
    }
}
