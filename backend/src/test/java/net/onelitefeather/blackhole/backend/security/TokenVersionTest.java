package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Only Entra v2 access tokens (ver 2.0) are accepted. */
@Tag("integration")
class TokenVersionTest extends ApiIntegrationTest {

    @Test
    void aVersionTwoTokenIsAccepted() {
        String token = this.jwt.delegated().claim("ver", "2.0").groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(200, get("/section", token).status(), "ver 2.0 with the configured issuer and audience");
    }

    @Test
    void aVersionOneTokenIsUnauthorized() {
        String token = this.jwt.delegated().claim("ver", "1.0").groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(401, get("/section", token).status(), "ver 1.0 is a v1 token");
    }

    @Test
    void aTokenWithoutAVersionClaimIsUnauthorized() {
        String token = this.jwt.delegated().without("ver").groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(401, get("/section", token).status(), "no ver claim");
    }
}
