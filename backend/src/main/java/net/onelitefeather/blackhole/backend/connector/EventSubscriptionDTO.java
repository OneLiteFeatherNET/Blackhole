package net.onelitefeather.blackhole.backend.connector;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Representation of a connector's event subscription. Never carries the signing secret - that's
 * only shown once, at creation time, via {@link EventSubscriptionCreatedDTO}.
 */
@Serdeable
@ReflectiveAccess
public record EventSubscriptionDTO(
        @NonNull UUID identifier,
        @NonNull UUID connectorId,
        @NonNull List<String> eventTypes,
        @NonNull String deliveryUrl,
        boolean active,
        int failureCount,
        @Nullable Long lastAttemptAt,
        @Nullable Long lastSuccessAt,
        @NonNull Map<String, Object> metaData
) {
}
