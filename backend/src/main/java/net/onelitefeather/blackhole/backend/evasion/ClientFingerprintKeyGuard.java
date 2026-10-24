package net.onelitefeather.blackhole.backend.evasion;

import io.micronaut.context.annotation.Context;

/**
 * Refuses to start with a malformed fingerprint key. An unset key is not an error: outside the
 * {@code dev} and {@code test} environments it switches fingerprinting off (the submit route answers 503,
 * like the IP salt does), and in a local environment {@link ClientFingerprintHasher} generates a random
 * key for this run. The key is never named in a message.
 */
@Context
public class ClientFingerprintKeyGuard {

    public ClientFingerprintKeyGuard(ClientFingerprintProperties properties) {
        check(properties.fingerprintKey());
    }

    /**
     * @throws IllegalStateException when a key is set but is not base64 of exactly 32 bytes
     */
    static void check(String key) {
        if (key != null && !key.isBlank()) {
            ClientFingerprintHasher.decode(key);
        }
    }
}
