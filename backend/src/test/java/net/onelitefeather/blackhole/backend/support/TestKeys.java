package net.onelitefeather.blackhole.backend.support;

import java.security.SecureRandom;
import java.util.Base64;

/** Key material for tests, generated at run time: no secret is ever written into the source. */
public final class TestKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestKeys() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Thirty-two random bytes. */
    public static byte[] randomBytes() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    /** Thirty-two random bytes as base64, the shape of {@code BLACKHOLE_BANID_KEY}. */
    public static String randomBase64() {
        return Base64.getEncoder().encodeToString(randomBytes());
    }
}
