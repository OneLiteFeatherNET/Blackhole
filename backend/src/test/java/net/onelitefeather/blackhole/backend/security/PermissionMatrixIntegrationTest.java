package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void aViewerAndTheProxyMayReadOneReport() {
        assertEquals(404, get("/report/" + UUID.randomUUID(), this.jwt.viewer()).status(), "viewer on GET /report/{identifier}");
        assertEquals(404, get("/report/" + UUID.randomUUID(), this.jwt.proxy()).status(), "proxy on GET /report/{identifier}");
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
    void onlyAnAdminMayWriteClientRulesButTheTeamAndTheProxyMayReadThem() {
        assertEquals(403, post("/client-rule", Map.of(), this.jwt.moderator()).status(), "moderator on a rule write");
        assertEquals(403, post("/client-rule", Map.of(), this.jwt.proxy()).status(), "proxy on a rule write");
        assertAuthorized(post("/client-rule", Map.of(), this.jwt.admin()), "admin on a rule write");
        assertEquals(200, get("/client-rule", this.jwt.viewer()).status(), "viewer on the rule list");
        assertEquals(200, get("/client-rule", this.jwt.proxy()).status(), "proxy on the rule list");
    }

    @Test
    void theClientRuleCatalogueIsWrittenByTheProxyAndReadByTheTeam() {
        assertAuthorized(put("/client-rule/catalogue", Map.of("proxyId", "Proxy-1"), this.jwt.proxy()), "proxy on PUT");
        assertEquals(403, put("/client-rule/catalogue", Map.of("proxyId", "Proxy-1"), this.jwt.admin()).status(), "admin on PUT");
        assertEquals(200, get("/client-rule/catalogue", this.jwt.viewer()).status(), "viewer on GET");
        assertEquals(403, get("/client-rule/catalogue", this.jwt.proxy()).status(), "proxy on GET");
    }

    @Test
    void theProxyReportsClientRuleHitsAndOnlyTheTeamReadsThem() {
        String owner = "ab".repeat(64);
        assertAuthorized(post("/client-rule/hit", Map.of("rule", "x", "owner", owner), this.jwt.proxy()), "proxy on a hit report");
        assertEquals(403, post("/client-rule/hit", Map.of("rule", "x", "owner", owner), this.jwt.admin()).status(), "admin on a hit report");
        assertEquals(403, get("/client-rule/x/hits", this.jwt.proxy()).status(), "proxy on a rule's hits");
        assertEquals(403, get("/client-rule/hits?owner=" + owner, this.jwt.proxy()).status(), "proxy on a player's hits");
        assertAuthorized(get("/client-rule/x/hits", this.jwt.viewer()), "viewer on a rule's hits");
        assertEquals(200, get("/client-rule/hits?owner=" + owner, this.jwt.viewer()).status(), "viewer on a player's hits");
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
    void onlyAModeratorMayShareOrRevokeEvidenceButAViewerMayReadAndPreview() {
        String base = "/case/unknown-punishment/evidence-shares";
        Map<String, Object> share = Map.of("evidenceId", EVIDENCE, "selection", List.of(0), "appellantSpeaker", "A", "confirmedMinimised", true);
        assertEquals(403, post(base, share, this.jwt.viewer()).status(), "viewer on a share");
        assertAuthorized(post(base, share, this.jwt.moderator()), "moderator on a share");
        assertEquals(403, post(base + "/" + EVIDENCE + "/revoke", Map.of("reason", "OTHER"), this.jwt.viewer()).status(), "viewer on a revoke");
        assertAuthorized(post(base + "/" + EVIDENCE + "/revoke", Map.of("reason", "OTHER"), this.jwt.moderator()), "moderator on a revoke");
        assertAuthorized(get(base, this.jwt.viewer()), "viewer on the share list");
        assertAuthorized(post(base + "/preview", share, this.jwt.viewer()), "viewer on the preview");
    }

    @Test
    void theProxyAndThePlayerHaveNoRuleOnTheShareRoutes() {
        String base = "/case/unknown-punishment/evidence-shares";
        Map<String, Object> share = Map.of("evidenceId", EVIDENCE, "selection", List.of(0), "appellantSpeaker", "A", "confirmedMinimised", true);
        assertEquals(403, post(base, share, this.jwt.proxy()).status(), "proxy on a share");
        assertEquals(403, get(base, this.jwt.proxy()).status(), "proxy on the list");
        assertEquals(403, post(base + "/preview", share, this.jwt.proxy()).status(), "proxy on the preview");
        assertEquals(403, post(base + "/" + EVIDENCE + "/revoke", Map.of("reason", "OTHER"), this.jwt.proxy()).status(), "proxy on a revoke");
        assertEquals(401, post(base, share, null).status(), "no token");
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

    // ---- the Discord bot: /discord/** and nothing else ----

    private static final String TICKET = UUID.randomUUID().toString();

    private String bot() {
        return this.jwt.appOnly().claim("oid", UUID.randomUUID().toString()).roles(TestJwtIssuer.DISCORD_ROLE).sign();
    }

    /** Every /discord/** call of the ticket facade, as {method, path, body}. */
    private List<Object[]> discordCalls() {
        return List.of(
                new Object[]{"POST", "/discord/tickets", Map.of("discordUserId", "111111111111111111", "banId", "ZZZZZZZZ", "statement", "x")},
                new Object[]{"GET", "/discord/tickets", null},
                new Object[]{"GET", "/discord/tickets/" + TICKET, null},
                new Object[]{"PATCH", "/discord/tickets/" + TICKET, Map.of("frozen", true)},
                new Object[]{"GET", "/discord/tickets/" + TICKET + "/outbox", null},
                new Object[]{"POST", "/discord/tickets/" + TICKET + "/messages", Map.of("items", List.of())},
                new Object[]{"POST", "/discord/tickets/" + TICKET + "/links", Map.of("seq", 1)},
                new Object[]{"POST", "/discord/tickets/" + TICKET + "/close", Map.of("count", 0)},
                new Object[]{"POST", "/discord/tickets/" + TICKET + "/protocol/ensure", Map.of()},
                new Object[]{"GET", "/discord/staff-links", null},
                new Object[]{"POST", "/discord/leak-check", Map.of("candidates", List.of("K7M29QXA"), "channelKind", "PUBLIC")});
    }

    private Reply call(Object[] call, String token) {
        String path = (String) call[1];
        return switch ((String) call[0]) {
            case "GET" -> get(path, token);
            case "PATCH" -> patch(path, call[2], token);
            default -> post(path, call[2], token);
        };
    }

    @Test
    void theBotMayCallEveryDiscordRoute() {
        for (Object[] call : discordCalls()) {
            assertAuthorized(call(call, bot()), "the bot on " + call[0] + " " + call[1]);
        }
    }

    @Test
    void staffOfEveryRoleAndTheProxyAreDeniedOnEveryDiscordRoute() {
        List<String> tokens = List.of(this.jwt.viewer(), this.jwt.moderator(), this.jwt.admin(), this.jwt.proxy(), this.jwt.metrics());
        for (Object[] call : discordCalls()) {
            for (String token : tokens) {
                assertEquals(403, call(call, token).status(), call[0] + " " + call[1] + " must refuse a token that is not the bot's");
            }
        }
    }

    @Test
    void theBotIsDeniedEverywhereOutsideTheDiscordFacade() {
        String bot = bot();
        assertEquals(403, get("/case", bot).status(), "the bot on the case list");
        assertEquals(403, get("/case/unknown-punishment", bot).status(), "the bot on a case");
        assertEquals(403, post("/case/unknown-punishment/messages", Map.of("text", "x"), bot).status(), "the bot on a case message");
        assertEquals(403, post("/case/unknown-punishment/participants/" + TICKET + "/erase", Map.of(), bot).status(), "the bot on a participant erase");
        assertEquals(403, get("/appeal", bot).status(), "the bot on the appeals");
        assertEquals(403, post("/punishment/apply", Map.of(), bot).status(), "the bot on a punishment");
        assertEquals(403, get("/punishment/unknown-punishment/ban-id", bot).status(), "the bot on a Ban ID reveal");
    }

    @Test
    void onlyAnAdminMayEraseAParticipant() {
        String path = "/case/unknown-punishment/participants/" + TICKET + "/erase";

        assertEquals(403, post(path, Map.of(), this.jwt.moderator()).status(), "moderator on a participant erase");
        assertEquals(403, post(path, Map.of(), this.jwt.proxy()).status(), "proxy on a participant erase");
        assertAuthorized(post(path, Map.of(), this.jwt.admin()), "admin on a participant erase");
    }
}
