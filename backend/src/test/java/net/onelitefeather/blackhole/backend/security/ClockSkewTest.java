package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Expiry and not-before are checked with at most 60 seconds of clock skew. The application reads
 * the system clock, so the offsets here are relative to it; they stay well clear of the 60 s edge.
 */
@Tag("integration")
class ClockSkewTest extends ApiIntegrationTest {

    @Test
    void aTokenValidOnlyInTwoMinutesIsUnauthorized() {
        Instant notBefore = Instant.now().plus(Duration.ofMinutes(2));
        String token = this.jwt.delegated().claim("nbf", Date.from(notBefore)).groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(401, get("/section", token).status(), "nbf 2 minutes ahead exceeds the 60 s skew bound");
    }

    @Test
    void aTokenThatExpiredThirtySecondsAgoIsStillAccepted() {
        Instant expired = Instant.now().minus(Duration.ofSeconds(30));
        String token = this.jwt.delegated().expiredAt(expired).groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(200, get("/section", token).status(), "exp 30 s ago is within the 60 s skew bound");
    }
}
