package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

import java.time.Duration;

/**
 * The proof-of-work challenge of the appeal portal ({@code blackhole.public.challenge}).
 *
 * @param enabled      whether sign-in and filing need a solved challenge; false only in the dev and test environments
 * @param key          the HMAC key that signs challenges, base64 of 32 random bytes; required outside dev and test
 * @param cost         PBKDF2 iterations the browser runs for one try
 * @param keyPrefix    the hex prefix the derived key must start with, an even number of characters
 * @param expiry       how long a challenge stays valid
 * @param storeTimeout the longest wait for the Dragonfly reserve of a solution
 */
@ConfigurationProperties("blackhole.public.challenge")
public record PublicChallengeProperties(
        boolean enabled,
        @NonNull String key,
        int cost,
        @NonNull String keyPrefix,
        @NonNull Duration expiry,
        @NonNull Duration storeTimeout
) {

    @ConfigurationInject
    public PublicChallengeProperties {
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "PublicChallengeProperties[enabled=" + this.enabled + ", key=***, cost=" + this.cost + ", keyPrefix=" + this.keyPrefix
                + ", expiry=" + this.expiry + ", storeTimeout=" + this.storeTimeout + "]";
    }
}
