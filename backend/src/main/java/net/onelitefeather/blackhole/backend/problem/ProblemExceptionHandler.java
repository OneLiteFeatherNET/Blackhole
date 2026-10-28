package net.onelitefeather.blackhole.backend.problem;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.HttpStatusHandler;
import io.micronaut.http.server.exceptions.JacksonExceptionHandler;
import io.micronaut.validation.exceptions.ConstraintExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers every uncaught failure of a route with a problem body (RFC 9457, {@link ProblemDetail#CONTENT_TYPE}).
 * The status is the one the failure carries; the body names the failure with fixed text and never echoes
 * the request, the rejected value or a stack trace.
 *
 * <p>All exception types go through {@link #respond}, so the mapping stays in one place. The framework has
 * more specific handlers for an {@link HttpStatusException}, for unreadable JSON and for constraint
 * violations, and those would answer first, so each of them is replaced by a bean that delegates here.
 */
@Singleton
@Replaces(ConstraintExceptionHandler.class)
public class ProblemExceptionHandler implements ExceptionHandler<Exception, HttpResponse<?>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProblemExceptionHandler.class);
    private static final String DETAIL = "The request could not be completed.";
    private static final String INVALID_DETAIL = "The request is not valid.";
    private static final String UNREADABLE_DETAIL = "The request body could not be read.";

    @Override
    public HttpResponse<?> handle(HttpRequest request, Exception exception) {
        return respond(request, exception);
    }

    /** Maps any failure of a route to its problem response. */
    static HttpResponse<ProblemDetail> respond(HttpRequest<?> request, Exception exception) {
        return respond(map(request.getPath(), exception));
    }

    private static ProblemMapper.Mapped map(String path, Exception exception) {
        if (exception instanceof HttpStatusException status) {
            return ProblemMapper.fromStatus(status.getStatus(), DETAIL, path, Map.of());
        }
        if (exception instanceof ConstraintViolationException violations) {
            List<Map<String, Object>> errors = violations.getConstraintViolations().stream()
                    .map(ProblemExceptionHandler::fieldError)
                    .toList();
            return ProblemMapper.fromStatus(HttpStatus.BAD_REQUEST, INVALID_DETAIL, path, Map.of("errors", errors));
        }
        if (exception instanceof JsonProcessingException) {
            return ProblemMapper.fromStatus(HttpStatus.BAD_REQUEST, UNREADABLE_DETAIL, path, Map.of());
        }
        LOGGER.error("unexpected failure on {}", path, exception);
        return ProblemMapper.unexpected(path);
    }

    private static Map<String, Object> fieldError(ConstraintViolation<?> violation) {
        String field = "";
        for (Path.Node node : violation.getPropertyPath()) {
            field = Objects.toString(node.getName(), "");
        }
        return Map.of("field", field, "message", violation.getMessage());
    }

    private static HttpResponse<ProblemDetail> respond(ProblemMapper.Mapped mapped) {
        return HttpResponse.<ProblemDetail>status(mapped.status())
                .contentType(MediaType.of(ProblemDetail.CONTENT_TYPE))
                .body(mapped.body());
    }

    /** Replaces the framework's answer to an {@link HttpStatusException}. */
    @Singleton
    @Replaces(HttpStatusHandler.class)
    static final class StatusProblemHandler implements ExceptionHandler<HttpStatusException, HttpResponse<?>> {

        @Override
        public HttpResponse<?> handle(HttpRequest request, HttpStatusException exception) {
            return respond(request, exception);
        }
    }

    /** Replaces the framework's answer to unreadable JSON. */
    @Singleton
    @Replaces(JacksonExceptionHandler.class)
    static final class JsonProblemHandler implements ExceptionHandler<JsonProcessingException, HttpResponse<?>> {

        @Override
        public HttpResponse<?> handle(HttpRequest request, JsonProcessingException exception) {
            return respond(request, exception);
        }
    }
}
