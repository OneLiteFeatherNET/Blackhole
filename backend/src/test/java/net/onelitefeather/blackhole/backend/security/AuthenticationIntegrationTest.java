package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Bearer token validation and {@code GET /auth/me}, with self-signed tokens and no network. */
@Tag("integration")
class AuthenticationIntegrationTest extends ApiIntegrationTest {

    private static final UUID OID = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000042");

    @Test
    void aValidTokenIsAuthenticatedAndRouted() {
        Reply reply = get("/auth/me", this.jwt.delegated().claim("oid", OID.toString()).groups(TestJwtIssuer.ADMIN_GROUP).sign());

        assertEquals(200, reply.status(), reply.body());
    }

    @Test
    void aRequestWithoutATokenIsUnauthorized() {
        assertEquals(401, get("/auth/me", null).status(), "no Authorization header");
    }

    @Test
    void anExpiredTokenIsUnauthorized() {
        String token = this.jwt.delegated().groups(TestJwtIssuer.ADMIN_GROUP).expiredAt(Instant.parse("2000-01-01T00:00:00Z")).sign();

        assertEquals(401, get("/auth/me", token).status(), "token expired in 2000");
    }

    @Test
    void aTokenFromAnotherIssuerIsUnauthorized() {
        String token = this.jwt.delegated().claim("iss", "https://evil.example.org/v2.0").sign();

        assertEquals(401, get("/auth/me", token).status(), "wrong issuer");
    }

    @Test
    void aTokenForAnotherAudienceIsUnauthorized() {
        String token = this.jwt.delegated().claim("aud", "some-other-api").sign();

        assertEquals(401, get("/auth/me", token).status(), "wrong audience");
    }

    @Test
    void aTokenSignedByAnUnknownKeyIsUnauthorized() {
        String token = this.jwt.delegated().groups(TestJwtIssuer.ADMIN_GROUP).signedByAnotherKey().sign();

        assertEquals(401, get("/auth/me", token).status(), "signature from a key the application does not trust");
    }

    @Test
    void aTokenWithAlgNoneIsUnauthorized() {
        String token = this.jwt.delegated().groups(TestJwtIssuer.ADMIN_GROUP).unsigned();

        assertEquals(401, get("/auth/me", token).status(), "alg none");
    }

    @Test
    void aMalformedTokenIsUnauthorized() {
        assertEquals(401, get("/auth/me", "not-a-jwt").status(), "garbage instead of a JWT");
    }

    @Test
    void meReturnsTheOidNameAndExpandedRolesOfAModerator() {
        String token = this.jwt.delegated().claim("oid", OID.toString()).claim("name", "Max Moderator")
                .groups(TestJwtIssuer.MODERATOR_GROUP).sign();

        Map<String, Object> me = get("/auth/me", token).json();

        assertEquals(OID.toString(), me.get("oid"));
        assertEquals("Max Moderator", me.get("displayName"));
        assertEquals(List.of("MODERATOR", "VIEWER"), me.get("roles"));
    }

    @Test
    void meAnswersWithAnEmptyRoleListForAUserInNoMappedGroup() {
        Reply reply = get("/auth/me", this.jwt.noGroup());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(List.of(), reply.json().get("roles"), "a valid token without a mapped group has no role");
    }

    @Test
    void meDescribesAMachineTokenWithoutADisplayName() {
        Reply reply = get("/auth/me", this.jwt.appOnly().without("name").roles(TestJwtIssuer.PROXY_ROLE).sign());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(List.of("PROXY"), reply.json().get("roles"));
        assertNull(reply.json().get("displayName"), "an app-only token has no name");
    }

    @Test
    void aTokenWithoutAnObjectIdCannotBeDescribed() {
        Reply reply = get("/auth/me", this.jwt.delegated().without("oid").sign());

        assertEquals(401, reply.status(), reply.body());
    }

    @Test
    void aUserInNoMappedGroupIsForbiddenOnEveryProtectedRoute() {
        String token = this.jwt.noGroup();

        assertEquals(403, get("/section", token).status(), "read route");
        assertEquals(403, get("/punishment", token).status(), "another read route");
        assertEquals(403, post("/punishment/preview", Map.of(), token).status(), "moderation route");
    }

    @Test
    void aGroupsOverageIsForbiddenWithItsReasonAndAnswersMe() {
        String token = this.jwt.delegated().claim("hasgroups", true).sign();

        Reply forbidden = get("/section", token);

        assertEquals(403, forbidden.status(), forbidden.body());
        assertEquals("groups-overage", forbidden.json().get("reason"), "the 403 says why");
        assertEquals(200, get("/auth/me", token).status(), "/auth/me still answers so a UI can explain it");
    }

    @Test
    void aPlainMissingRoleIsForbiddenWithoutTheOverageReason() {
        Reply forbidden = get("/section", this.jwt.noGroup());

        assertFalse(forbidden.body().contains("groups-overage"), "no overage marker, no overage reason: " + forbidden.body());
    }
}
