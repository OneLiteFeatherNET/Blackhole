package net.onelitefeather.blackhole.backend.problem;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.context.ServerRequestContext;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicError;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The one place that turns a failure into a status and a problem body (design D5). Controllers and
 * the global handler call it; nothing builds a problem body by hand.
 */
public final class ProblemMapper {

    /** The HTTP status to send and the problem body to send with it. */
    public record Mapped(HttpStatus status, ProblemDetail body) {

        /**
         * The problem as a response. A route's declared body type is its success body; a problem is the other body the same
         * route sends, so the type parameter is the route's and the runtime body is the problem.
         */
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> response() {
            return HttpResponse.<T>status(this.status)
                    .contentType(MediaType.of(ProblemDetail.CONTENT_TYPE))
                    .body((T) this.body);
        }
    }

    private static final String PUBLIC_DETAIL = "The request could not be completed; see code for the reason.";
    private static final String UNEXPECTED_DETAIL = "An unexpected error occurred.";

    private ProblemMapper() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * An expected failure of a service. The message becomes the detail, the code (when there is one)
     * the type, title and {@code code} member.
     */
    public static Mapped fromError(ErrorResponse error, HttpStatus status, @Nullable String code, String instance) {
        return fromError(error, status, code, instance, Map.of());
    }

    /**
     * As {@link #fromError(ErrorResponse, HttpStatus, String, String)}, with further extension members such as
     * {@code availableFrom}. A {@code null} extension is left out of the body.
     */
    public static Mapped fromError(
            ErrorResponse error, HttpStatus status, @Nullable String code, String instance, Map<String, Object> extensions
    ) {
        Objects.requireNonNull(error, "error");
        Map<String, Object> members = new HashMap<>(extensions);
        if (code == null) {
            return of(status, ProblemType.forStatus(status), status.getReason(), error.errorMessage(), instance, members);
        }
        members.put("code", code);
        return of(status, ProblemType.forCode(code), ProblemType.titleOf(code), error.errorMessage(), instance, members);
    }

    /**
     * An expected failure named by its kind: the status is the kind's. An {@link ErrorKind#INVALID_CODE} keeps its name as the
     * machine {@code code}, the one the link clients branch on.
     */
    public static Mapped fromKind(ErrorResponse error, ErrorKind kind, String instance) {
        String code = kind == ErrorKind.INVALID_CODE ? kind.name() : null;
        return fromError(error, kind.status(), code, instance);
    }

    /** An expected failure of a public route. The status comes from the kind; {@code availableFrom} is kept when present. */
    public static Mapped fromPublicError(PublicError error, String instance) {
        Objects.requireNonNull(error, "error");
        Map<String, Object> extensions = new HashMap<>();
        extensions.put("code", error.code());
        extensions.put("availableFrom", error.availableFrom());
        HttpStatus status = error.kind().status();
        return of(status, ProblemType.forCode(error.code()), ProblemType.titleOf(error.code()), PUBLIC_DETAIL, instance, extensions);
    }

    /** A failure without a service behind it: a framework status, a validation failure, and so on. */
    public static Mapped fromStatus(HttpStatus status, String detail, String instance, Map<String, Object> extensions) {
        return of(status, ProblemType.forStatus(status), status.getReason(), detail, instance, extensions);
    }

    /** Any failure nobody expected: always a 500 with a fixed detail, never the cause. */
    public static Mapped unexpected(String instance) {
        return fromStatus(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_DETAIL, instance, Map.of());
    }

    /** The path of the request being served, the {@code instance} of a problem a controller answers with. */
    public static String instance() {
        return ServerRequestContext.currentRequest()
                .map(HttpRequest::getPath)
                .orElseThrow(() -> new IllegalStateException("a problem is answered outside a request"));
    }

    private static Mapped of(
            HttpStatus status, String type, String title, String detail, String instance, Map<String, Object> extensions
    ) {
        return new Mapped(status, new ProblemDetail(type, title, status.getCode(), detail, instance, extensions));
    }
}
