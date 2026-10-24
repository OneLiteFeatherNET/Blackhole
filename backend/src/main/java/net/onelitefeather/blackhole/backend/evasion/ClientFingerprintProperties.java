package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

/**
 * Client fingerprint settings ({@code blackhole.clientrules}).
 *
 * @param fingerprintKey the secret (32 bytes, base64) the fingerprint key derives from; a key of its own,
 *                       neither the IP salt nor the Ban ID key
 */
@ConfigurationProperties("blackhole.clientrules")
public record ClientFingerprintProperties(@Nullable String fingerprintKey) {

    @ConfigurationInject
    public ClientFingerprintProperties {
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "ClientFingerprintProperties[fingerprintKey=***]";
    }
}
