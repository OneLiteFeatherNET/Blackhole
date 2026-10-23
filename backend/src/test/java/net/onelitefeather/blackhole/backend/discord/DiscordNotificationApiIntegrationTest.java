package net.onelitefeather.blackhole.backend.discord;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The bot's notification read facade over HTTP: only the DISCORD role reads it, and it never leaks the full hash. */
@Tag("integration")
class DiscordNotificationApiIntegrationTest extends ApiIntegrationTest {

    private String proxy() {
        return this.jwt.appOnly().claim("oid", UUID.randomUUID().toString()).roles(TestJwtIssuer.PROXY_ROLE).sign();
    }

    @Test
    void theBotReadsASubmittedReportWithoutTheFullHash() {
        String reported = "d".repeat(128);
        Reply submitted = post("/report", Map.of("reporterHash", "e".repeat(128), "reportedHash", reported, "category", "GRIEFING",
                "description", "blew up spawn", "metaData", Map.of("reportedName", "Griefer_01")), proxy());
        assertEquals(200, submitted.status(), submitted.body());

        Reply reply = get("/discord/notifications/report/" + submitted.json().get("identifier"), this.jwt.discord());

        assertEquals(200, reply.status(), reply.body());
        assertEquals("Griefer_01", reply.json().get("playerName"), "recorded name");
        assertEquals("dddddddd", reply.json().get("playerRef"), "pseudonym");
        assertEquals("GRIEFING", reply.json().get("category"), "category");
        assertEquals("OPEN", reply.json().get("status"), "status");
        assertFalse(reply.body().contains(reported), "no full hash");
    }

    @Test
    void unknownItemsAreNotFound() {
        assertEquals(404, get("/discord/notifications/report/" + UUID.randomUUID(), this.jwt.discord()).status(), "report");
        assertEquals(404, get("/discord/notifications/appeal/" + UUID.randomUUID(), this.jwt.discord()).status(), "appeal");
    }

    @Test
    void onlyTheBotMayReadNotifications() {
        String path = "/discord/notifications/report/" + UUID.randomUUID();

        assertEquals(403, get(path, this.jwt.admin()).status(), "admin");
        assertEquals(403, get(path, proxy()).status(), "proxy");
        assertEquals(403, get(path, this.jwt.viewer()).status(), "viewer");
    }
}
