package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

import java.time.Duration;

/**
 * Staff account linking ({@code blackhole.security.staff-link}).
 *
 * @param ttl how long an issued link code stays valid
 */
@ConfigurationProperties("blackhole.security.staff-link")
public record StaffLinkProperties(@NonNull Duration ttl) {

    @ConfigurationInject
    public StaffLinkProperties {
    }
}
