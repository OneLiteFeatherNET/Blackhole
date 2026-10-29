package net.onelitefeather.blackhole.backend.publicbans.auth;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies a ban-list client password against an Argon2id PHC string with the documented parameters
 * ({@code m=19456} KiB, {@code t=2}, {@code p=1}, 32-byte output, version 0x13). Any other algorithm or parameter set is
 * refused at load time, so one scheme is in use everywhere.
 */
public final class Argon2ClientHashVerifier implements ClientHashVerifier {

    private static final String PREFIX = "$argon2id$v=19$";
    private static final String PARAMETERS = "m=19456,t=2,p=1";
    private static final int MEMORY_KIB = 19456;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int HASH_BYTES = 32;
    private static final int MIN_SALT_BYTES = 8;
    private static final String REFUSAL = "the value is not an Argon2id PHC string with the documented parameters";
    private static final byte[] DUMMY_SALT = "blackhole-dummy-salt".getBytes(StandardCharsets.US_ASCII);

    private final byte[] salt;
    private final byte[] expected;

    private Argon2ClientHashVerifier(byte[] salt, byte[] expected) {
        this.salt = salt;
        this.expected = expected;
    }

    /**
     * @param phc the stored hash, {@code $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>}
     * @throws IllegalArgumentException when the string is not that scheme; the message never contains the value
     */
    public static Argon2ClientHashVerifier of(String phc) {
        if (phc == null || !phc.startsWith(PREFIX)) {
            throw new IllegalArgumentException(REFUSAL);
        }
        String[] parts = phc.substring(PREFIX.length()).split("\\$", -1);
        if (parts.length != 3 || !parts[0].equals(PARAMETERS)) {
            throw new IllegalArgumentException(REFUSAL);
        }
        byte[] salt = decode(parts[1]);
        byte[] hash = decode(parts[2]);
        if (salt.length < MIN_SALT_BYTES || hash.length != HASH_BYTES) {
            throw new IllegalArgumentException(REFUSAL);
        }
        return new Argon2ClientHashVerifier(salt, hash);
    }

    /** The PHC string of a password with the given salt, for the offline tool and for tests. */
    public static String encode(String password, byte[] salt) {
        return PREFIX + PARAMETERS + "$" + base64(salt) + "$" + base64(derive(password, salt));
    }

    /** A verifier over a fixed hash that matches no real password; an unknown client name is checked against it. */
    public static Argon2ClientHashVerifier dummy() {
        return of(encode("no-client-uses-this-password", DUMMY_SALT));
    }

    @Override
    public boolean matches(String password) {
        return MessageDigest.isEqual(derive(password, this.salt), this.expected);
    }

    private static byte[] derive(String password, byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] out = new byte[HASH_BYTES];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }

    private static byte[] decode(String text) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(REFUSAL);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
}
