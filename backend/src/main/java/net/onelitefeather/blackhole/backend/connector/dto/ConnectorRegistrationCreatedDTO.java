package net.onelitefeather.blackhole.backend.connector.dto;

import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Response to a connector registration. {@code oauth2ClientSecret} is the only time the
 * plaintext secret is ever shown - only the SHA-512 hash is persisted.
 */
@Serdeable
@ReflectiveAccess
public record ConnectorRegistrationCreatedDTO(
        ConnectorRegistrationDTO connector,
        String oauth2ClientSecret
) {
}
