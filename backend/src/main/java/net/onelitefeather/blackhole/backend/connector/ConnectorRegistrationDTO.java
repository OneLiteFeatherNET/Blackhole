package net.onelitefeather.blackhole.backend.connector;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Representation of a registered connector. Deliberately never carries the client secret (or
 * its hash) - that's only ever shown once, at creation time, via {@link ConnectorRegistrationCreatedDTO}.
 */
@Serdeable
@ReflectiveAccess
public record ConnectorRegistrationDTO(
        @NonNull UUID identifier,
        @NonNull String name,
        @NonNull String oauth2ClientId,
        @NonNull List<String> scopes,
        @NonNull ConnectorStatus status,
        @NonNull Map<String, Object> metaData
) {
}
