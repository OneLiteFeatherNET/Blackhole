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
    void theProxyMayReadAndAcknowledgeReportFeedback() {
        assertEquals(200, get("/report/feedback/" + "ab".repeat(64), this.jwt.proxy()).status(), "proxy on GET /report/feedback/{owner}");
        assertEquals(204, post("/report/feedback/" + UUID.randomUUID() + "/ack", Map.of(), this.jwt.proxy()).status(), "proxy on the acknowledgement");
    }

    @Test
    void humansCannotReadOrAcknowledgeReportFeedback() {
        assertEquals(403, get("/report/feedback/" + "ab".repeat(64), this.jwt.admin()).status(), "admin on a proxy-only route");
        assertEquals(403, post("/report/feedback/" + UUID.randomUUID() + "/ack", Map.of(), this.jwt.moderator()).status(), "moderator on a proxy-only route");
    }

    @Test
    void aViewerMayReadOneReportButTheProxyMayNot() {
        assertEquals(404, get("/report/" + UUID.randomUUID(), this.jwt.viewer()).status(), "viewer on GET /report/{identifier}");
        assertEquals(403, get("/report/" + UUID.randomUUID(), this.jwt.proxy()).status(), "proxy on a staff read");
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
    void theProxyMayReportTheCloudNetCatalogue() {
        assertAuthorized(put("/cloudnet/catalogue", Map.of("proxyId", "Proxy-1"), this.jwt.proxy()), "proxy on PUT /cloudnet/catalogue");
    }

    @Test
    void aViewerCannotReportTheCloudNetCatalogue() {
        assertEquals(403, put("/cloudnet/catalogue", Map.of("proxyId", "Proxy-1"), this.jwt.viewer()).status(), "viewer on a proxy-only route");
    }

    @Test
    void anAdminCannotReportTheCloudNetCatalogue() {
        assertEquals(403, put("/cloudnet/catalogue", Map.of("proxyId", "Proxy-1"), this.jwt.admin()).status(), "humans may not forge the catalogue");
    }

    @Test
    void aViewerCanReadTheCloudNetCatalogue() {
        assertEquals(200, get("/cloudnet/catalogue", this.jwt.viewer()).status(), "viewer on a read route");
    }

    @Test
    void theProxyCannotReadTheCloudNetCatalogue() {
        assertEquals(403, get("/cloudnet/catalogue", this.jwt.proxy()).status(), "the catalogue is for the editor");
    }

    @Test
    void aViewerMayListTheActivePunishments() {
        assertEquals(200, get("/punishment/active", this.jwt.viewer()).status(), "viewer on the active punishment overview");
    }

    @Test
    void theProxyCannotListTheActivePunishments() {
        assertEquals(403, get("/punishment/active", this.jwt.proxy()).status(), "the overview is for the staff UI");
    }

    @Test
    void aViewerMayReadACase() {
        assertAuthorized(get("/case/unknown-punishment", this.jwt.viewer()), "viewer on GET /case/{reference}");
    }

    @Test
    void aViewerCannotAddANote() {
        assertEquals(403, post("/case/unknown-punishment/notes", Map.of("text", "x"), this.jwt.viewer()).status(), "viewer on a case write");
    }

    @Test
    void aModeratorMayAddANote() {
        assertAuthorized(post("/case/unknown-punishment/notes", Map.of("text", "x"), this.jwt.moderator()), "moderator on a case write");
    }

    @Test
    void aModeratorCannotRedactANote() {
        assertEquals(403, post("/case/unknown-punishment/notes/" + EVIDENCE + "/redact", Map.of("reason", "OTHER"), this.jwt.moderator()).status(),
                "moderator on redaction");
    }

    @Test
    void anAdminMayRedactANote() {
        assertAuthorized(post("/case/unknown-punishment/notes/" + EVIDENCE + "/redact", Map.of("reason", "OTHER"), this.jwt.admin()), "admin on redaction");
    }

    @Test
    void aViewerCannotReplyToThePlayer() {
        assertEquals(403, post("/case/unknown-punishment/messages", Map.of("text", "x"), this.jwt.viewer()).status(), "viewer on a message");
    }

    @Test
    void aModeratorMayReplyToThePlayer() {
        assertAuthorized(post("/case/unknown-punishment/messages", Map.of("text", "x"), this.jwt.moderator()), "moderator on a message");
    }

    @Test
    void aViewerCannotMarkALeakButAModeratorMay() {
        assertEquals(403, post("/case/unknown-punishment/ban-id/leak", Map.of("reason", "OTHER"), this.jwt.viewer()).status(), "viewer on a leak mark");
        assertAuthorized(post("/case/unknown-punishment/ban-id/leak", Map.of("reason", "OTHER"), this.jwt.moderator()), "moderator on a leak mark");
    }

    @Test
    void onlyAnAdminMayReissueABanId() {
        assertEquals(403, post("/case/unknown-punishment/ban-id/reissue", Map.of("reason", "OTHER"), this.jwt.moderator()).status(), "moderator on a reissue");
        assertAuthorized(post("/case/unknown-punishment/ban-id/reissue", Map.of("reason", "OTHER"), this.jwt.admin()), "admin on a reissue");
    }

    @Test
    void onlyAnAdminMayRedactAMessageOrErasethePlayerSide() {
        assertEquals(403, post("/case/unknown-punishment/messages/" + EVIDENCE + "/redact", Map.of("reason", "OTHER"), this.jwt.moderator()).status(),
                "moderator on a message redaction");
        assertAuthorized(post("/case/unknown-punishment/messages/" + EVIDENCE + "/redact", Map.of("reason", "OTHER"), this.jwt.admin()),
                "admin on a message redaction");
        assertEquals(403, post("/case/unknown-punishment/transcript/erase-player", Map.of("reason", "OTHER"), this.jwt.moderator()).status(),
                "moderator on an erase");
        assertAuthorized(post("/case/unknown-punishment/transcript/erase-player", Map.of("reason", "OTHER"), this.jwt.admin()), "admin on an erase");
    }

    @Test
    void onlyAnAdminMayExportATranscript() {
        assertEquals(403, get("/case/unknown-punishment/transcript", this.jwt.moderator()).status(), "moderator on an export");
        assertEquals(403, get("/case/unknown-punishment/transcript", this.jwt.viewer()).status(), "viewer on an export");
        assertAuthorized(get("/case/unknown-punishment/transcript", this.jwt.admin()), "admin on an export");
    }

    @Test
    void theProxyHasNoRuleOnTheConversationRoutes() {
        assertEquals(403, post("/case/unknown-punishment/messages", Map.of("text", "x"), this.jwt.proxy()).status());
        assertEquals(403, post("/case/unknown-punishment/ban-id/leak", Map.of("reason", "OTHER"), this.jwt.proxy()).status());
        assertEquals(403, post("/case/unknown-punishment/ban-id/reissue", Map.of("reason", "OTHER"), this.jwt.proxy()).status());
        assertEquals(403, get("/case/unknown-punishment/transcript", this.jwt.proxy()).status());
    }

    @Test
    void aViewerMayReadTheStoredProtocolsButTheProxyMayNot() {
        assertAuthorized(get("/case/unknown-punishment/protocol", this.jwt.viewer()), "viewer on the protocol list");
        assertAuthorized(get("/case/unknown-punishment/protocol/" + EVIDENCE, this.jwt.viewer()), "viewer on opening a protocol");
        assertAuthorized(get("/case/unknown-punishment/protocol/" + EVIDENCE + "/download", this.jwt.viewer()), "viewer on a protocol download");
        assertAuthorized(get("/case/unknown-punishment/protocol", this.jwt.moderator()), "moderator on the protocol list");
        assertEquals(403, get("/case/unknown-punishment/protocol", this.jwt.proxy()).status(), "proxy on the protocol list");
        assertEquals(403, get("/case/unknown-punishment/protocol/" + EVIDENCE, this.jwt.proxy()).status(), "proxy on opening a protocol");
        assertEquals(403, get("/case/unknown-punishment/protocol/" + EVIDENCE + "/download", this.jwt.proxy()).status(), "proxy on a download");
    }

    @Test
    void aRequestWithoutATokenIsRefusedOnTheProtocolRoutes() {
        assertEquals(401, get("/case/unknown-punishment/protocol", null).status());
    }

    @Test
    void theProxyCannotReadACase() {
        assertEquals(403, get("/case/unknown-punishment", this.jwt.proxy()).status(), "proxy has no case rule");
    }

    @Test
    void anAdminCanListTheStaffActors() {
        assertEquals(200, get("/admin/staff-actor", this.jwt.admin()).status(), "admin on the staff actor list");
    }

    @Test
    void aModeratorCannotListTheStaffActors() {
        assertEquals(403, get("/admin/staff-actor", this.jwt.moderator()).status(), "moderator on an admin route");
    }

    @Test
    void aViewerCannotListTheStaffActors() {
        assertEquals(403, get("/admin/staff-actor", this.jwt.viewer()).status(), "viewer on an admin route");
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

    @Test
    void noStaffRoleOrTheProxyOpensTheFactsOfAPlayerSession() {
        for (String token : java.util.List.of(this.jwt.admin(), this.jwt.moderator(), this.jwt.viewer(), this.jwt.proxy())) {
            int status = get("/public/punishment", token).status();
            assertTrue(status == 401 || status == 403, "a staff or proxy token on /public/punishment must be refused, got " + status);
        }
    }

    @Test
    void aPlayerSessionTokenIsNotAStaffTokenOnAnyStaffRoute() {
        String sessionShapedToken = "bhps_" + "A".repeat(43);

        assertEquals(401, get("/section", sessionShapedToken).status(), "an unknown session token on a staff route");
        assertEquals(401, get("/auth/me", sessionShapedToken).status(), "even the any-token identity route");
    }
}
