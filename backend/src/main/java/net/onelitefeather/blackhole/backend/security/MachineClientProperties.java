package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

import java.util.List;

/**
 * The client ids ({@code azp} of the token) allowed to act with each machine role
 * ({@code blackhole.security.roles.machine-clients}). An app-only token carrying a machine role
 * value is granted the role only when its {@code azp} is on the list of that role; an empty list
 * grants the role to nobody.
 *
 * @param proxy   clients allowed to act as PROXY (the Velocity plugin)
 * @param metrics clients allowed to act as METRICS (the Prometheus scraper)
 * @param discord clients allowed to act as DISCORD (the Discord bot)
 */
@ConfigurationProperties("blackhole.security.roles.machine-clients")
public record MachineClientProperties(
        @NonNull List<String> proxy,
        @NonNull List<String> metrics,
        @NonNull List<String> discord
) {

    @ConfigurationInject
    public MachineClientProperties {
    }
}
