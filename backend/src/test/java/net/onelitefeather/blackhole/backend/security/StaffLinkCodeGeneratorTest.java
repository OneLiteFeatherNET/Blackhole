package net.onelitefeather.blackhole.backend.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffLinkCodeGeneratorTest {

    private final StaffLinkCodeGenerator generator = new StaffLinkCodeGenerator(new Random(42));

    @Test
    void aGeneratedCodeHasEightCharactersFromTheCrockfordAlphabet() {
        for (int i = 0; i < 200; i++) {
            String code = this.generator.generate();

            assertEquals(8, code.length(), "eight characters");
            assertTrue(code.chars().allMatch(c -> StaffLinkCodeGenerator.ALPHABET.indexOf(c) >= 0), "alphabet only: " + code);
        }
    }

    @Test
    void theAlphabetHasNoLookAlikeLetters() {
        for (char excluded : "ILOU".toCharArray()) {
            assertEquals(-1, StaffLinkCodeGenerator.ALPHABET.indexOf(excluded), excluded + " is not in the alphabet");
        }
        assertEquals(32, StaffLinkCodeGenerator.ALPHABET.length(), "base32");
    }

    @Test
    void theDisplayFormSplitsTheCodeInTwoHalves() {
        assertEquals("ABCD-EFGH", StaffLinkCodeGenerator.display("ABCDEFGH"), "shown with a dash");
    }

    @Test
    void inputIsCaseInsensitiveAndTheDashIsOptional() {
        assertEquals(Optional.of("ABCD2345"), StaffLinkCodeGenerator.normalize("abcd-2345"), "lower case with dash");
        assertEquals(Optional.of("ABCD2345"), StaffLinkCodeGenerator.normalize("ABCD2345"), "no dash");
    }

    @Test
    void lookAlikesAreReadTheCrockfordWay() {
        assertEquals(Optional.of("11110000"), StaffLinkCodeGenerator.normalize("ILli-OOoo"), "I and L are 1, O is 0");
    }

    @Test
    void inputThatIsNotACodeIsRejected() {
        assertEquals(Optional.empty(), StaffLinkCodeGenerator.normalize(null), "null");
        assertEquals(Optional.empty(), StaffLinkCodeGenerator.normalize("ABCD-EFG"), "too short");
        assertEquals(Optional.empty(), StaffLinkCodeGenerator.normalize("ABCD-EFGHJ"), "too long");
        assertEquals(Optional.empty(), StaffLinkCodeGenerator.normalize("ABCD-EFGU"), "U is not in the alphabet");
        assertEquals(Optional.empty(), StaffLinkCodeGenerator.normalize("ABCD EFGH"), "a space is not a dash");
    }

    @Test
    void theHashIsSha256HexAndDoesNotContainTheCode() {
        String hash = StaffLinkCodeGenerator.hash("ABCD2345");

        assertEquals(64, hash.length(), "SHA-256 as hex");
        assertEquals(hash, StaffLinkCodeGenerator.hash("ABCD2345"), "deterministic");
        assertFalse(hash.toUpperCase().contains("ABCD2345"), "the code is not recoverable by eye");
    }
}
