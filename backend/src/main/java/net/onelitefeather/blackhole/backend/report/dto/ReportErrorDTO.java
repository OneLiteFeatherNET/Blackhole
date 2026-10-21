package net.onelitefeather.blackhole.backend.report.dto;

import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

/**
 * The body of a failed report resolution, so every expected failure is distinguishable by its message
 * and kind instead of a bare status code.
 *
 * @param errorMessage what went wrong
 * @param kind         the kind of failure, which also decides the HTTP status
 */
@Serdeable
@ReflectiveAccess
public record ReportErrorDTO(String errorMessage, ErrorKind kind) implements ErrorResponse {
}
