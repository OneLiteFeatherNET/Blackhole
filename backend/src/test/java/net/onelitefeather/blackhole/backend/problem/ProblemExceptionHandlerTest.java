package net.onelitefeather.blackhole.backend.problem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.validation.constraints.Min;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.Roles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The global handler over HTTP: an {@code HttpStatusException}, a constraint violation and an
 * unexpected failure each become a problem with the right status, and none of them echoes the
 * message, the input or a stack trace.
 */
class ProblemExceptionHandlerTest extends ApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROBLEMS = "https://blackhole.onelitefeather.net/problems/";
    private static final String NOT_FOUND_PATH = "/test-problem-probe/not-found";

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("test.problem-probe", true);
    }

    private RawHttp.Reply probe(String path) {
        return RawHttp.send(this.server.getURL(), "GET", path, null, this.jwt.admin());
    }

    @Test
    void anHttpStatusExceptionBecomesAProblemWithItsStatus() throws Exception {
        RawHttp.Reply reply = probe(NOT_FOUND_PATH);
        Map<String, Object> body = json(reply);

        assertEquals(404, reply.status(), "the status of the thrown HttpStatusException is kept");
        assertEquals(PROBLEMS + "not-found", body.get("type"), "a status without a code is typed by its kind");
        assertEquals("Not Found", body.get("title"), "the title is the status reason");
        assertEquals(404, body.get("status"), "the body repeats the status");
        assertEquals(NOT_FOUND_PATH, body.get("instance"), "the instance is the request path");
    }

    @Test
    void aProblemResponseIsServedAsProblemJson() {
        RawHttp.Reply reply = probe(NOT_FOUND_PATH);

        assertEquals("application/problem+json", reply.contentType(), "the content type is the problem media type: " + reply.body());
    }

    @Test
    void theDetailOfAnHttpStatusExceptionIsFixedAndNeverItsMessage() {
        RawHttp.Reply reply = probe(NOT_FOUND_PATH);

        assertFalse(reply.body().contains("secret-input-42"), "the exception message is not echoed: " + reply.body());
    }

    @Test
    void aConstraintViolationBecomesAValidationProblemWithItsFieldErrors() throws Exception {
        RawHttp.Reply reply = probe("/test-problem-probe/range?size=0");
        Map<String, Object> body = json(reply);

        assertEquals(400, reply.status(), "a violated constraint is a 400: " + reply.body());
        assertEquals(PROBLEMS + "validation", body.get("type"), "a constraint violation is typed as validation");
        assertEquals(List.of(Map.of("field", "size", "message", "must be greater than or equal to 1")), body.get("errors"),
                "the errors name the field and the fixed message, not the rejected value");
        assertFalse(reply.body().contains("\"0\""), "the rejected value is not echoed");
    }

    @Test
    void anUnexpectedFailureBecomesAFixedInternalErrorWithoutStackTrace() throws Exception {
        RawHttp.Reply reply = probe("/test-problem-probe/boom");
        Map<String, Object> body = json(reply);

        assertEquals(500, reply.status(), "an unknown failure is a 500: " + reply.body());
        assertEquals(PROBLEMS + "internal-error", body.get("type"), "the type is the internal error");
        assertEquals("An unexpected error occurred.", body.get("detail"), "the detail is the fixed sentence");
        assertFalse(reply.body().contains("secret internal detail"), "the failure message is not echoed");
        assertFalse(reply.body().contains("Exception"), "no exception class name is sent: " + reply.body());
        assertFalse(reply.body().contains("\tat "), "no stack trace is sent");
    }

    private static Map<String, Object> json(RawHttp.Reply reply) throws JsonProcessingException {
        return JSON.readValue(reply.body(), new TypeReference<Map<String, Object>>() {
        });
    }

    /** Routes that fail on purpose; registered only for this test class. */
    @Controller("/test-problem-probe")
    @Requires(property = "test.problem-probe", value = "true")
    @Validated
    static class ProbeController {

        @Get("/not-found")
        @Secured(Roles.VIEWER)
        String notFound() {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "secret-input-42");
        }

        @Get("/range")
        @Secured(Roles.VIEWER)
        String range(@QueryValue @Min(1) int size) {
            return "ok";
        }

        @Get("/boom")
        @Secured(Roles.VIEWER)
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
