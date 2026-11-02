package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.env.Environment;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * Refuses to start with a challenge setting that would weaken the check outside the {@code dev} and {@code test}
 * environments: the switch cannot be off, the key must be set and be base64 of 32 bytes, and the difficulty and
 * the lifetime must be usable. Like {@code BanIdKeyGuard}, it never names the key in a message. In a local
 * environment an unset key is allowed; a fresh random key is then generated for this run.
 */
@Context
public class ChallengeKeyGuard {

    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of(Environment.DEVELOPMENT, Environment.TEST);
    private static final int KEY_BYTES = 32;
    private static final int MAX_COST = 10_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String key;

    @Inject
    public ChallengeKeyGuard(Environment environment, PublicChallengeProperties properties) {
        this(check(properties, environment.getActiveNames()));
    }

    /** For a test or a caller that already resolved the key. */
    ChallengeKeyGuard(String key) {
        this.key = key;
    }

    /** The base64 key that signs challenges. */
    public String key() {
        return this.key;
    }

    /** Checks the whole configuration and returns the key to use. */
    static String check(PublicChallengeProperties properties, Set<String> activeEnvironments) {
        requireSwitch(properties.enabled(), activeEnvironments);
        requireTunables(properties.cost(), properties.expiry(), properties.storeTimeout());
        requirePrefix(properties.keyPrefix());
        return resolveKey(properties.key(), activeEnvironments);
    }

    /**
     * The key to sign with: the configured one, checked, or in a local environment without one a fresh random key.
     *
     * @throws IllegalStateException when the key is unset outside the local environments, or is malformed or short
     */
    public static String resolveKey(String key, Set<String> activeEnvironments) {
        requireKey(key, activeEnvironments);
        if (key == null || key.isBlank()) {
            byte[] random = new byte[KEY_BYTES];
            RANDOM.nextBytes(random);
            return Base64.getEncoder().encodeToString(random);
        }
        return key;
    }

    /** @throws IllegalStateException when the key is unset outside a local environment, or is not base64 of 32 bytes */
    static void requireKey(String key, Set<String> activeEnvironments) {
        if (key == null || key.isBlank()) {
            if (activeEnvironments.stream().noneMatch(LOCAL_ENVIRONMENTS::contains)) {
                throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_KEY is not set. Outside the dev and test environments the "
                        + "challenge key is required: 32 random bytes, base64 (for example: openssl rand -base64 32).");
            }
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_KEY is not valid base64 of 32 random bytes.", e);
        }
        if (decoded.length < KEY_BYTES) {
            throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_KEY decodes to fewer than 32 bytes.");
        }
    }

    /** @throws IllegalStateException when the check is off outside a local environment */
    static void requireSwitch(boolean enabled, Set<String> activeEnvironments) {
        if (!enabled && activeEnvironments.stream().noneMatch(LOCAL_ENVIRONMENTS::contains)) {
            throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_ENABLED=false is only allowed in the dev and test environments.");
        }
    }

    /** @throws IllegalStateException when the prefix is not an even number of hex characters */
    static void requirePrefix(String prefix) {
        if (prefix == null || prefix.length() % 2 != 0 || !prefix.matches("(?i)[0-9a-f]*")) {
            throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_KEY_PREFIX must be an even number of hex characters.");
        }
    }

    /** @throws IllegalStateException when the cost, the expiry or the store timeout is out of range */
    static void requireTunables(int cost, Duration expiry, Duration storeTimeout) {
        if (cost < 1 || cost > MAX_COST) {
            throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_COST must be between 1 and " + MAX_COST + ".");
        }
        if (expiry == null || expiry.isZero() || expiry.isNegative()) {
            throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_EXPIRY must be a positive duration.");
        }
        if (storeTimeout == null || storeTimeout.isZero() || storeTimeout.isNegative()) {
            throw new IllegalStateException("BLACKHOLE_PUBLIC_CHALLENGE_STORE_TIMEOUT must be a positive duration.");
        }
    }
}
