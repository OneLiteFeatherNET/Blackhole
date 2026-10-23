package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

/**
 * Top-level security settings ({@code blackhole.security}).
 *
 * @param tenantId            the Entra tenant; when set, tokens of another tenant ({@code tid}) are refused
 * @param allowInsecureIssuer lets a non-dev, non-test environment use a plain http or localhost issuer
 * @param jwksUrl             where the signing keys of the issuer are published
 */
@ConfigurationProperties("blackhole.security")
public record SecurityProperties(
        @NonNull String tenantId,
        boolean allowInsecureIssuer,
        @NonNull String jwksUrl
) {

    @ConfigurationInject
    public SecurityProperties {
    }
}
