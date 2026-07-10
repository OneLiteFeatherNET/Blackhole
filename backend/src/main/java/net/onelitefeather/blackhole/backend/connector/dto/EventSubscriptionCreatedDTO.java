package net.onelitefeather.blackhole.backend.connector.dto;

import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Response to an event subscription creation. {@code signingSecret} is the only time the
 * plaintext HMAC secret is ever shown.
 */
@Serdeable
@ReflectiveAccess
public record EventSubscriptionCreatedDTO(
        EventSubscriptionDTO subscription,
        String signingSecret
) {
}
