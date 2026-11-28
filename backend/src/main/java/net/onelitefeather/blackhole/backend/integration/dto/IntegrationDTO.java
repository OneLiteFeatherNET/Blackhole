package net.onelitefeather.blackhole.backend.integration.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import net.onelitefeather.blackhole.backend.integration.IntegrationSpec;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Wire contract of {@code /integration/registrations}: which third-party detection source a Entra client id may
 * report for, and where. The client secret is never part of this contract; it stays in Entra.
 */
@Schema(description = "An integration registration: the source system, the Entra client id, the kinds and the server scope it may report")
@Serdeable
public sealed interface IntegrationDTO {

    @Schema(name = "IntegrationCreateRequest", description = "A new registration, enabled. The source system and the client id cannot change later")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull @NotBlank String sourceSystem,
            @NonNull @NotBlank String displayName,
            @NonNull @NotBlank String entraClientId,
            @Nullable List<String> allowedKinds,
            @Nullable List<String> allowedTasks,
            @Nullable List<String> allowedStaticServers
    ) implements IntegrationDTO {

        public IntegrationSpec toSpec() {
            return new IntegrationSpec(this.sourceSystem, this.displayName, this.entraClientId, true,
                    orEmpty(this.allowedKinds), orEmpty(this.allowedTasks), orEmpty(this.allowedStaticServers));
        }
    }

    @Schema(name = "IntegrationUpdateRequest", description = "Replaces the editable fields of the registration: name, enabled, kinds and server scope")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(
            @NonNull @NotBlank String displayName,
            boolean enabled,
            @Nullable List<String> allowedKinds,
            @Nullable List<String> allowedTasks,
            @Nullable List<String> allowedStaticServers
    ) implements IntegrationDTO {
    }

    @Schema(description = "A stored registration")
    @Serdeable
    record Response(
            long id,
            String sourceSystem,
            String displayName,
            String entraClientId,
            boolean enabled,
            List<String> allowedKinds,
            List<String> allowedTasks,
            List<String> allowedStaticServers,
            Instant createdAt,
            Instant updatedAt
    ) implements IntegrationDTO {
    }

    @Serdeable
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements IntegrationDTO, ErrorResponse {
    }

    private static List<String> orEmpty(@Nullable List<String> values) {
        return Objects.requireNonNullElse(values, List.of());
    }
}
