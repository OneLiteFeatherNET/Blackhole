package net.onelitefeather.blackhole.backend.publicapi.challenge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicChallengeDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The issuer with a fixed clock. The library signs with the given key and reads no time when it issues. */
class ChallengeIssuerTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static ChallengeIssuer issuer(boolean enabled, TestClock clock) {
        PublicChallengeProperties properties = new PublicChallengeProperties(enabled, KEY, 10, "00", Duration.ofMinutes(5), Duration.ofMillis(500));
        return new ChallengeIssuer(new ChallengeKeyGuard(KEY), properties, clock, new ChallengeMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void anIssuedChallengeCarriesThePurposeInItsSignedData() {
        PublicChallengeDTO result = issuer(true, new TestClock()).issue("sign-in");

        assertEquals("sign-in", assertInstanceOf(PublicChallengeDTO.Issued.class, result).parameters().data().purpose(),
                "the purpose is part of the data the signature covers");
    }

    @Test
    void anIssuedChallengeExpiresAfterTheConfiguredLifetime() {
        TestClock clock = new TestClock();
        PublicChallengeDTO result = issuer(true, clock).issue("appeal");

        assertEquals(clock.instant().getEpochSecond() + 300, assertInstanceOf(PublicChallengeDTO.Issued.class, result).parameters().expiresAt(),
                "the expiry is the clock's now plus the configured five minutes");
    }

    @Test
    void anIssuedChallengeUsesTheAgreedAlgorithm() {
        PublicChallengeDTO result = issuer(true, new TestClock()).issue("sign-in");

        assertEquals("PBKDF2/SHA-256", assertInstanceOf(PublicChallengeDTO.Issued.class, result).parameters().algorithm(),
                "the algorithm is the one the widget and the library share");
    }

    @Test
    void anIssuedChallengeIsSigned() {
        PublicChallengeDTO result = issuer(true, new TestClock()).issue("sign-in");

        assertFalse(assertInstanceOf(PublicChallengeDTO.Issued.class, result).signature().isEmpty(), "the challenge carries a signature");
    }

    @Test
    void anUnknownPurposeIsABadRequest() {
        PublicChallengeDTO result = issuer(true, new TestClock()).issue("admin");

        PublicChallengeDTO.Error error = assertInstanceOf(PublicChallengeDTO.Error.class, result);
        assertEquals("UNKNOWN_PURPOSE", error.code(), "an unknown purpose has its own code");
        assertEquals(PublicErrorKind.VALIDATION, error.kind(), "and answers 400");
    }

    @Test
    void aMissingPurposeIsAnUnknownPurpose() {
        PublicChallengeDTO result = issuer(true, new TestClock()).issue(null);

        assertEquals("UNKNOWN_PURPOSE", assertInstanceOf(PublicChallengeDTO.Error.class, result).code(), "no purpose is an unknown purpose");
    }

    @Test
    void aDisabledCheckAnswersEnabledFalse() {
        PublicChallengeDTO result = issuer(false, new TestClock()).issue("sign-in");

        assertFalse(assertInstanceOf(PublicChallengeDTO.Disabled.class, result).enabled(), "the widget is not shown when the check is off");
    }
}
