package net.onelitefeather.blackhole.backend.problem;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicError;
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
        Objects.requireNonNull(error, "error");
        if (code == null) {
            return of(status, ProblemType.forStatus(status), status.getReason(), error.errorMessage(), instance, Map.of());
        }
        return of(status, ProblemType.forCode(code), ProblemType.titleOf(code), error.errorMessage(), instance, Map.of("code", code));
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

    private static Mapped of(
            HttpStatus status, String type, String title, String detail, String instance, Map<String, Object> extensions
    ) {
        return new Mapped(status, new ProblemDetail(type, title, status.getCode(), detail, instance, extensions));
    }
}
