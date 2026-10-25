package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.token.Claims;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeClaimsValidatorTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Claims claims(Instant exp, Instant nbf) {
        Map<String, Object> map = new HashMap<>();
        if (exp != null) {
            map.put("exp", Date.from(exp));
        }
        if (nbf != null) {
            map.put("nbf", Date.from(nbf));
        }
        return new Claims() {
            @Override
            public Object get(String name) {
                return map.get(name);
            }

            @Override
            public java.util.Set<String> names() {
                return map.keySet();
            }

            @Override
            public boolean contains(String name) {
                return map.containsKey(name);
            }
        };
    }

    private final TimeClaimsValidator validator = new TimeClaimsValidator(60, CLOCK);

    @Test
    void aSkewAboveTheBoundStopsStartup() {
        assertThrows(IllegalStateException.class, () -> new TimeClaimsValidator(61, CLOCK),
                "61 s is above the 60 s bound");
    }

    @Test
    void aNegativeSkewStopsStartup() {
        assertThrows(IllegalStateException.class, () -> new TimeClaimsValidator(-1, CLOCK),
                "a negative skew is meaningless");
    }

    @Test
    void aSmallerSkewIsAllowed() {
        assertFalse(new TimeClaimsValidator(0, CLOCK).validate(claims(NOW.minusSeconds(1), null), null),
                "with no skew, 1 s past exp is expired");
    }

    @Test
    void expiredInsideTheSkewIsAccepted() {
        assertTrue(validator.validate(claims(NOW.minusSeconds(59), null), null), "59 s past exp is inside 60 s");
    }

    @Test
    void expiredBeyondTheSkewIsRefused() {
        assertFalse(validator.validate(claims(NOW.minusSeconds(61), null), null), "61 s past exp is outside 60 s");
    }

    @Test
    void notYetValidInsideTheSkewIsAccepted() {
        assertTrue(validator.validate(claims(NOW.plusSeconds(3600), NOW.plusSeconds(59)), null),
                "nbf 59 s ahead is inside 60 s");
    }

    @Test
    void notYetValidBeyondTheSkewIsRefused() {
        assertFalse(validator.validate(claims(NOW.plusSeconds(3600), NOW.plusSeconds(61)), null),
                "nbf 61 s ahead is outside 60 s");
    }

    @Test
    void aTokenWithoutExpiryIsRefused() {
        assertFalse(validator.validate(claims(null, null), null), "exp is mandatory");
    }
}
