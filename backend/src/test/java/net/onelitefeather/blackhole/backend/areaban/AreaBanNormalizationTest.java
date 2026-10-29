package net.onelitefeather.blackhole.backend.areaban;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AreaBanNormalizationTest {

    @Test
    void anAsnWithUppercasePrefixNormalisesToDigits() {
        assertEquals(Optional.of("16276"), AreaBanNormalizer.asn("AS16276"), "AS16276 must normalise to 16276");
    }

    @Test
    void anAsnWithLowercasePrefixNormalisesToDigits() {
        assertEquals(Optional.of("16276"), AreaBanNormalizer.asn("as16276"), "as16276 must normalise to 16276");
    }

    @Test
    void anAsnWithoutPrefixIsKept() {
        assertEquals(Optional.of("16276"), AreaBanNormalizer.asn("16276"), "16276 must stay 16276");
    }

    @Test
    void aCountryCodeIsUppercased() {
        assertEquals(Optional.of("DE"), AreaBanNormalizer.country("de"), "de must normalise to DE");
    }

    @Test
    void aNonNumericAsnIsRefused() {
        assertFalse(AreaBanNormalizer.asn("ASX").isPresent(), "a non-numeric ASN must be refused");
    }

    @Test
    void aZeroAsnIsRefused() {
        assertFalse(AreaBanNormalizer.asn("AS0").isPresent(), "AS0 is not a real network and must be refused");
    }

    @Test
    void aThreeLetterCountryCodeIsRefused() {
        assertFalse(AreaBanNormalizer.country("DEU").isPresent(), "a three-letter code must be refused");
    }
}
