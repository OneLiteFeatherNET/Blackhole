package net.onelitefeather.blackhole.backend.proxy;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** One proxy's last SignedVelocity report as kept in the shared cache. */
@Serdeable
@ReflectiveAccess
public record ProxyCapabilityRecord(String proxyId, boolean present, @Nullable String version, Instant reportedAt) {
}
