package net.onelitefeather.blackhole.backend.banid;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.env.Environment;

import java.util.Set;

/**
 * Refuses to start without a real Ban ID key outside the {@code dev} and {@code test}
 * environments: the key must be set and be base64 of 32 bytes. In a local environment an unset key
 * is allowed; {@link BanIdCrypto} then generates a random key for this run. Like the
 * insecure-issuer guard, it keeps local conveniences out of production. The key is never named in
 * a message.
 */
@Context
public class BanIdKeyGuard {

    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of(Environment.DEVELOPMENT, Environment.TEST);

    public BanIdKeyGuard(Environment environment, BanIdProperties properties) {
        check(properties.key(), environment.getActiveNames());
    }

    /**
     * @throws IllegalStateException when the key is malformed, or is unset and no local environment is active
     */
    static void check(String key, Set<String> activeEnvironments) {
        if (key == null || key.isBlank()) {
            if (activeEnvironments.stream().noneMatch(LOCAL_ENVIRONMENTS::contains)) {
                throw new IllegalStateException("BLACKHOLE_BANID_KEY is not set. Outside the dev and test environments the "
                        + "Ban ID key is required: 32 random bytes, base64 (for example: openssl rand -base64 32).");
            }
            return;
        }
        BanIdCrypto.decode(key);
    }
}
