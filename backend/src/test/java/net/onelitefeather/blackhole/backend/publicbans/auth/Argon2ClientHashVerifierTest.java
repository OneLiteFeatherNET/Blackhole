package net.onelitefeather.blackhole.backend.publicbans.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Argon2ClientHashVerifierTest {

    private static final byte[] SALT = "fixed-test-salt-16".getBytes(StandardCharsets.US_ASCII);

    @Test
    void theCorrectPasswordIsAcceptedForAHashBuiltWithTheFixedParameters() {
        Argon2ClientHashVerifier verifier = Argon2ClientHashVerifier.of(Argon2ClientHashVerifier.encode("correct horse", SALT));

        assertTrue(verifier.matches("correct horse"), "the password that built the hash is accepted");
    }

    @Test
    void aWrongPasswordIsRefused() {
        Argon2ClientHashVerifier verifier = Argon2ClientHashVerifier.of(Argon2ClientHashVerifier.encode("correct horse", SALT));

        assertFalse(verifier.matches("wrong horse"), "any other password is refused");
    }

    @Test
    void aPhcStringWithOtherParametersIsRefusedAtLoadTime() {
        String other = Argon2ClientHashVerifier.encode("correct horse", SALT).replace("m=19456,t=2,p=1", "m=65536,t=3,p=1");

        assertThrows(IllegalArgumentException.class, () -> Argon2ClientHashVerifier.of(other),
                "a hash with other cost parameters is not the documented scheme");
    }

    @Test
    void aPhcStringWithAnotherAlgorithmIsRefusedAtLoadTime() {
        String argon2i = Argon2ClientHashVerifier.encode("correct horse", SALT).replace("$argon2id$", "$argon2i$");

        assertThrows(IllegalArgumentException.class, () -> Argon2ClientHashVerifier.of(argon2i),
                "only Argon2id is accepted");
    }

    @Test
    void theRefusalMessageDoesNotEchoTheHash() {
        String garbage = "$argon2id$not-a-real-hash-value";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Argon2ClientHashVerifier.of(garbage));

        assertEquals("the value is not an Argon2id PHC string with the documented parameters", failure.getMessage(),
                "the message says what is wrong without echoing the value");
    }
}
