package net.onelitefeather.blackhole.backend.security;

import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Makes, normalizes and hashes staff link codes: 8 characters of Crockford base32 (the digits and
 * the letters without {@code I L O U}), 40 bits from a {@link SecureRandom}, shown as {@code ABCD-EFGH}.
 * Input is read the way Crockford specifies: case-insensitive, dashes ignored, {@code I} and
 * {@code L} read as {@code 1}, {@code O} as {@code 0}. Only {@link #hash} of a code is ever stored;
 * the code itself is never logged.
 */
@Singleton
public class StaffLinkCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int LENGTH = 8;

    private final RandomGenerator random;

    public StaffLinkCodeGenerator() {
        this(new SecureRandom());
    }

    StaffLinkCodeGenerator(RandomGenerator random) {
        this.random = random;
    }

    /** A new code in its normalized form: eight alphabet characters, no dash. */
    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(this.random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** The code as shown to a person: {@code ABCD-EFGH}. */
    public static String display(String code) {
        return code.substring(0, LENGTH / 2) + "-" + code.substring(LENGTH / 2);
    }

    /**
     * The normalized form of what a person typed.
     *
     * @return the eight alphabet characters, or empty when the input is not a code at all
     */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        StringBuilder code = new StringBuilder(LENGTH);
        for (char raw : input.toCharArray()) {
            char c = Character.toUpperCase(raw);
            if (c == '-') {
                continue;
            }
            c = switch (c) {
                case 'I', 'L' -> '1';
                case 'O' -> '0';
                default -> c;
            };
            if (ALPHABET.indexOf(c) < 0 || code.length() == LENGTH) {
                return Optional.empty();
            }
            code.append(c);
        }
        return code.length() == LENGTH ? Optional.of(code.toString()) : Optional.empty();
    }

    /** SHA-256 of a normalized code as lower case hex: what is stored instead of the code. */
    public static String hash(String normalizedCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedCode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
