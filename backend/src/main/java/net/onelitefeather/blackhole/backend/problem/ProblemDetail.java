package net.onelitefeather.blackhole.backend.problem;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An RFC 9457 problem details body, the body of every error response once the routes migrate.
 *
 * <p>The five standard members are fixed. Extension members (the machine {@code code}, the data a
 * client needs to act on it) are flattened to the top level of the JSON object under their own
 * names. An extension whose value is {@code null} is left out, so a body never carries a null member.
 *
 * @param type the problem type URI, see {@link ProblemType}
 * @param title the fixed title of the type
 * @param status the HTTP status of the response, repeated in the body
 * @param detail a fixed sentence about this failure; never echoes the request
 * @param instance the request path
 * @param extensions additional members; {@code null} values are dropped
 */
@Serdeable
public record ProblemDetail(
        @NonNull String type,
        @NonNull String title,
        int status,
        @NonNull String detail,
        @NonNull String instance,
        @JsonIgnore Map<String, Object> extensions
) {

    /** The media type of every problem response (RFC 9457). */
    public static final String CONTENT_TYPE = "application/problem+json";

    public ProblemDetail {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(instance, "instance");
        Map<String, Object> members = new LinkedHashMap<>();
        if (extensions != null) {
            extensions.forEach((name, value) -> {
                if (value != null) {
                    members.put(name, value);
                }
            });
        }
        extensions = Collections.unmodifiableMap(members);
    }

    /** The extension members as top-level JSON members. */
    @JsonAnyGetter
    public Map<String, Object> extensionMembers() {
        return this.extensions;
    }
}
