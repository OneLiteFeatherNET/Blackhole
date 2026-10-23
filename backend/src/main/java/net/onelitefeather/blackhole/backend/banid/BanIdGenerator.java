package net.onelitefeather.blackhole.backend.banid;

import jakarta.inject.Singleton;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/** Draws Ban ID candidates: 8 alphabet characters from a {@link SecureRandom}. */
@Singleton
public class BanIdGenerator {

    private final RandomGenerator random;

    public BanIdGenerator() {
        this(new SecureRandom());
    }

    /** The seam for tests: a scripted source of randomness. */
    BanIdGenerator(RandomGenerator random) {
        this.random = random;
    }

    /** A new candidate in its normalised form. Uniqueness is the caller's business. */
    public String generate() {
        StringBuilder code = new StringBuilder(BanIdCodec.LENGTH);
        for (int i = 0; i < BanIdCodec.LENGTH; i++) {
            code.append(BanIdCodec.ALPHABET.charAt(this.random.nextInt(BanIdCodec.ALPHABET.length())));
        }
        return code.toString();
    }
}
