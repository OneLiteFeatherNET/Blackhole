package net.onelitefeather.blackhole.backend.evidence.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.5", "10.0.0.1", "255.255.255.255", "0.0.0.0"})
    void ipv4AddressesAreDetected(String ip) {
        assertTrue(PiiGuard.containsIp("from " + ip + " today"), ip);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:db8:85a3:0:0:8a2e:370:7334", "2001:db8::8a2e:370:7334", "::1", "fe80::1", "::ffff:203.0.113.5", "2001:db8::"
    })
    void ipv6AddressesAreDetected(String ip) {
        assertTrue(PiiGuard.containsIp("from " + ip + " today"), ip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999.1.1.1", "1.2.3", "1.2.3.4.5", "version 1.2", "12:30:45", "10:15", "::", "a:b:c", "player_1.2.3"})
    void textThatOnlyLooksLikeAnAddressIsNotFlagged(String text) {
        assertFalse(PiiGuard.containsIp(text), text + " is not an IP address");
    }

    @Test
    void redactionReplacesEveryAddressAndKeepsTheRest() {
        assertEquals("join [ip] and [ip], bye", PiiGuard.redactIps("join 203.0.113.5 and 2001:db8::1, bye"));
    }

    @Test
    void anAddressAtTheEndOfASentenceIsRedactedWithoutItsDot() {
        assertEquals("his ip is [ip].", PiiGuard.redactIps("his ip is 2001:db8::1."));
        assertEquals("his ip is [ip].", PiiGuard.redactIps("his ip is 203.0.113.5."));
    }

    @Test
    void redactingTextWithoutAddressesChangesNothing() {
        assertEquals("gg wp at 12:30", PiiGuard.redactIps("gg wp at 12:30"));
        assertEquals(null, PiiGuard.redactIps(null));
    }

    @Test
    void canonicalUuidsAreDetectedInAnyCaseAndInsideText() {
        assertTrue(PiiGuard.containsUuid("123e4567-e89b-12d3-a456-426614174000"));
        assertTrue(PiiGuard.containsUuid("player 123E4567-E89B-12D3-A456-426614174000 flagged"));
    }

    @Test
    void hashesAndPlainHexAreNotUuids() {
        assertFalse(PiiGuard.containsUuid("ab".repeat(64)), "a sha-512 owner hash");
        assertFalse(PiiGuard.containsUuid("123e4567e89b12d3a456426614174000"), "undashed hex is not canonical");
    }

    @Test
    void theViolationPointerWalksMapsAndLists() {
        Map<String, Object> value = Map.of("values", Map.of("list", List.of("ok", "bad 10.0.0.1")));

        assertEquals(Optional.of("/values/list/1"), PiiGuard.firstViolation("", value));
    }

    @Test
    void pointerSegmentsAreEscaped() {
        Map<String, Object> value = Map.of("a/b", "10.0.0.1");

        assertEquals(Optional.of("/a~1b"), PiiGuard.firstViolation("", value));
    }

    @Test
    void cleanStructuresHaveNoViolation() {
        assertEquals(Optional.empty(), PiiGuard.firstViolation("", Map.of("check", "fly", "n", 3, "ok", true)));
    }
}
