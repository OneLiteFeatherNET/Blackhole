package net.onelitefeather.blackhole.backend.security.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of {@code /auth}: who the verified caller is. There is no request variant, the
 * caller is identified by the bearer token alone.
 */
@Schema(description = "The identity and roles of the caller, taken from the verified access token")
@Serdeable
public sealed interface AuthDTO {

    @Schema(name = "AuthMeResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull UUID oid,
            @Nullable String displayName,
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<String> roles
    ) implements AuthDTO {
    }

    @Schema(name = "AuthError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements AuthDTO, ErrorResponse {
    }
}
