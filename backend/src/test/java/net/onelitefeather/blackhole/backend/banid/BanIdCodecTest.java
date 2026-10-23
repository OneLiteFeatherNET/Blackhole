package net.onelitefeather.blackhole.backend.banid;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BanIdCodecTest {

    @ParameterizedTest(name = "[{0}] reads as {1}")
    @CsvSource({
            "K7M29XYZ, K7M29XYZ",
            "k7m29xyz, K7M29XYZ",
            "#K7M29XYZ, K7M29XYZ",
            "' #k7m2 9xyz ', K7M29XYZ",
            "O0I1L1AB, 00111 1AB",
    })
    void typedInputIsNormalised(String typed, String expected) {
        assertEquals(Optional.of(expected.replace(" ", "")), BanIdCodec.normalize(typed));
    }

    @ParameterizedTest(name = "[{0}] matches nothing")
    @CsvSource(value = {
            "K7M29XY", "K7M29XYZA", "##K7M29XYZ", "K7M29XYU", "K7M2-9XY", "K7M29XY!", "''",
    })
    void anythingElseMatchesNothing(String typed) {
        assertEquals(Optional.empty(), BanIdCodec.normalize(typed));
    }

    @Test
    void nullMatchesNothing() {
        assertEquals(Optional.empty(), BanIdCodec.normalize(null));
    }

    @Test
    void theAlphabetIsCrockfordBase32() {
        assertEquals(32, BanIdCodec.ALPHABET.length());
        for (char excluded : "ILOU".toCharArray()) {
            assertEquals(-1, BanIdCodec.ALPHABET.indexOf(excluded), excluded + " is not in the alphabet");
        }
    }
}
