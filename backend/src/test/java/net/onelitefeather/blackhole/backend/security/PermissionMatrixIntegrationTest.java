package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the role checks over HTTP. Which role each route needs is pinned by
 * {@link RouteCoverageIntegrationTest}; this checks that the checks hold. An authorized call may
 * still fail with 400 or 404 on its own merits - only 401 and 403 mean "refused by security".
 */
@Tag("integration")
class PermissionMatrixIntegrationTest extends ApiIntegrationTest {

    private static final String EVIDENCE = UUID.randomUUID().toString();

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("test.unsecured-probe", true);
    }

    private static void assertAuthorized(Reply reply, String what) {
        assertTrue(reply.status() != 401 && reply.status() != 403, what + " must pass security, got " + reply.status() + " " + reply.body());
    }

    @Test
    void aViewerCannotApplyAPunishment() {
        assertEquals(403, post("/punishment/apply", Map.of(), this.jwt.viewer()).status(), "viewer on a moderation route");
    }

    @Test
    void aViewerCanReadSections() {
        assertEquals(200, get("/section", this.jwt.viewer()).status(), "viewer on a read route");
    }

    @Test
    void aModeratorCannotCreateASection() {
        assertEquals(403, post("/section", Map.of(), this.jwt.moderator()).status(), "moderator on an admin route");
    }

    @Test
    void aModeratorMayCallModerationRoutes() {
        assertAuthorized(post("/punishment/preview", Map.of(), this.jwt.moderator()), "moderator on /punishment/preview");
    }

    @Test
    void anAdminMayCallAdminRoutes() {
        assertAuthorized(post("/section", Map.of(), this.jwt.admin()), "admin on /section");
    }

    @Test
    void aModeratorCannotDeleteEvidence() {
        assertEquals(403, delete("/evidence/" + EVIDENCE, this.jwt.moderator()).status(), "moderator on erasure");
    }

    @Test
    void theProxyCannotDeleteEvidence() {
        assertEquals(403, delete("/evidence/" + EVIDENCE, this.jwt.proxy()).status(), "proxy on erasure");
    }

    @Test
    void theProxyCannotResolveAReport() {
        assertEquals(403, post("/report/" + UUID.randomUUID() + "/resolve", Map.of(), this.jwt.proxy()).status(), "proxy on a review route");
    }

    @Test
    void theProxyMayApplyAPunishment() {
        assertAuthorized(post("/punishment/apply", Map.of(), this.jwt.proxy()), "proxy on /punishment/apply");
    }

    @Test
    void theProxyMayReadSections() {
        assertEquals(200, get("/section", this.jwt.proxy()).status(), "proxy on a read route");
    }

    @Test
    void aMachineTokenWithAHumanRoleValueHasNoRole() {
        String token = this.jwt.appOnly().roles(TestJwtIssuer.MODERATOR_GROUP, TestJwtIssuer.ADMIN_GROUP).sign();

        assertEquals(403, get("/section", token).status(), "app-only token must not act as a moderator");
    }

    @Test
    void aUserTokenCarryingTheProxyRoleIsNotTheProxy() {
        String token = this.jwt.delegated().groups(TestJwtIssuer.VIEWER_GROUP).roles(TestJwtIssuer.PROXY_ROLE).sign();

        assertEquals(403, post("/punishment/apply", Map.of(), token).status(), "a viewer with the proxy value in roles");
    }

    @Test
    void aRouteWithoutAnyRuleIsRejectedEvenForAnAdmin() {
        Reply reply = get("/test-unsecured-probe", this.jwt.admin());

        assertNotEquals(200, reply.status(), "an unannotated route must not be served: " + reply.body());
        assertTrue(reply.status() == 401 || reply.status() == 403, "rejected by security, got " + reply.status());
    }
}
