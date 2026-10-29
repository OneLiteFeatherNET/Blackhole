package net.onelitefeather.blackhole.backend.intel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelPrefixTest {

    @Test
    void hostBitsAreClearedSoTheNetworkIsCanonical() {
        IntelPrefix prefix = IntelPrefix.parseCidr("198.51.100.77/24").orElseThrow();

        assertEquals("198.51.100.0/24", prefix.toString(), "the stored network has no host bits");
    }

    @Test
    void aPrefixLengthAboveTheFamilyMaximumIsRejected() {
        assertTrue(IntelPrefix.parseCidr("198.51.100.0/33").isEmpty(), "an IPv4 prefix above 32 is not CIDR");
        assertTrue(IntelPrefix.parseCidr("2001:db8::/129").isEmpty(), "an IPv6 prefix above 128 is not CIDR");
    }
}
