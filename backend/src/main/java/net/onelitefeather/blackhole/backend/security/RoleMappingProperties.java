package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

import java.util.List;

/**
 * Which claim carries the roles of each token kind and which claim values grant which internal
 * role ({@code blackhole.security.roles}). The human values are Entra group object ids by default;
 * switching to app-role assignment is a configuration change only.
 *
 * @param claimHuman     claim read on delegated tokens (those with {@code scp})
 * @param claimMachine   claim read on app-only tokens
 * @param admin          values granting ADMIN on delegated tokens
 * @param moderator      values granting MODERATOR on delegated tokens
 * @param viewer         values granting VIEWER on delegated tokens
 * @param metrics        values granting METRICS on delegated tokens
 * @param machineProxy   values granting PROXY on app-only tokens
 * @param machineMetrics values granting METRICS on app-only tokens
 * @param machineDiscord values granting DISCORD on app-only tokens
 */
@ConfigurationProperties("blackhole.security.roles")
public record RoleMappingProperties(
        @NonNull String claimHuman,
        @NonNull String claimMachine,
        @NonNull List<String> admin,
        @NonNull List<String> moderator,
        @NonNull List<String> viewer,
        @NonNull List<String> metrics,
        @NonNull List<String> machineProxy,
        @NonNull List<String> machineMetrics,
        @NonNull List<String> machineDiscord
) {

    @ConfigurationInject
    public RoleMappingProperties {
    }
}
