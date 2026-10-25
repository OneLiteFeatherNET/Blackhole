package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code GET /inbox} over HTTP: the four sections and the role matrix of the route. */
@Tag("integration")
class InboxApiIntegrationTest extends ApiIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void theInboxAnswersTheFourSectionsWithTheirCountsAndItems() {
        Reply reply = get("/inbox", this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        Map<String, Object> inbox = reply.json();
        for (String section : new String[] {"needsAnswer", "openAppeals", "newReports", "unassignedCases"}) {
            Map<String, Object> body = (Map<String, Object>) inbox.get(section);
            assertTrue(body != null && body.get("count") != null && body.get("items") != null,
                    section + " must carry count and items: " + reply.body());
        }
        assertTrue(inbox.get("generatedAt") != null, "the response is stamped: " + reply.body());
    }

    @Test
    void aViewerMayReadTheInbox() {
        assertEquals(200, get("/inbox", this.jwt.viewer()).status());
    }

    @Test
    void aModeratorAndAnAdminMayReadTheInbox() {
        assertEquals(200, get("/inbox", this.jwt.moderator()).status());
        assertEquals(200, get("/inbox", this.jwt.admin()).status());
    }

    @Test
    void theProxyIsDeniedTheInbox() {
        assertEquals(403, get("/inbox", this.jwt.proxy()).status(), "the proxy has no staff inbox");
    }

    @Test
    void theDiscordBotIsDeniedTheInbox() {
        assertEquals(403, get("/inbox", this.jwt.discord()).status(), "the bot is limited to /discord/**");
    }

    @Test
    void aCallerWithoutATokenIsRefused() {
        assertEquals(401, get("/inbox", null).status(), "every route but health needs a token");
    }
}
