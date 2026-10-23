package net.onelitefeather.blackhole.backend.banid;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

/**
 * Ban ID settings ({@code blackhole.banid}).
 *
 * @param key the secret (32 bytes, base64) the lookup hash and the display encryption derive from
 */
@ConfigurationProperties("blackhole.banid")
public record BanIdProperties(@NonNull String key) {

    @ConfigurationInject
    public BanIdProperties {
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "BanIdProperties[key=***]";
    }
}
