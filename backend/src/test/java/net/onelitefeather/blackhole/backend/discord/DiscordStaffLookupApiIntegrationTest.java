package net.onelitefeather.blackhole.backend.discord;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's staff lookup over HTTP against the real database: the bot alone may ask, the answer carries the staff oid and whether a press is
 * allowed, and the Discord id is never in it. Each test uses its own random ids.
 */
@Tag("integration")
class DiscordStaffLookupApiIntegrationTest extends ApiIntegrationTest {

    private static String discordUserId() {
        return Long.toString(100_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(900_000_000_000_000_000L));
    }

    private String bot() {
        return this.jwt.appOnly().azp(TestJwtIssuer.DISCORD_CLIENT).claim("oid", UUID.randomUUID().toString()).roles(TestJwtIssuer.DISCORD_ROLE).sign();
    }

    private String delegated(UUID oid, String group) {
        return this.jwt.delegated().claim("oid", oid.toString()).groups(group).sign();
    }

    /** Links a staff member to a Discord account; a request of the member with the given group takes their role snapshot. */
    private void givenLinkedStaff(UUID oid, String discordId, String group) {
        this.context.getBean(DiscordLinkRepository.class).save(
                new DiscordLinkEntity(UUID.randomUUID(), DiscordLinkKind.STAFF, oid.toString(), discordId, System.currentTimeMillis()));
        assertEquals(200, get("/staff-discord-link/me", delegated(oid, group)).status(), "the member's request takes the role snapshot");
    }

    @Test
    void theBotGetsTheOidAndEligibilityOfALinkedModeratorWithoutTheDiscordId() {
        UUID oid = UUID.randomUUID();
        String discordId = discordUserId();
        givenLinkedStaff(oid, discordId, TestJwtIssuer.MODERATOR_GROUP);

        Reply reply = get("/discord/staff-links/" + discordId, bot());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(oid.toString(), reply.json().get("oid"), "the staff oid is answered");
        assertEquals(true, reply.json().get("eligible"), "a fresh moderator may act now");
        assertFalse(reply.body().contains(discordId), "the Discord id is not echoed back");
    }

    @Test
    void aLinkedViewerIsAnsweredButNotEligible() {
        UUID oid = UUID.randomUUID();
        String discordId = discordUserId();
        givenLinkedStaff(oid, discordId, TestJwtIssuer.VIEWER_GROUP);

        Reply reply = get("/discord/staff-links/" + discordId, bot());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(false, reply.json().get("eligible"), "a viewer may not act from Discord");
    }

    @Test
    void aDiscordUserWithoutALiveLinkIs404ForTheBot() {
        Reply reply = get("/discord/staff-links/" + discordUserId(), bot());

        assertEquals(404, reply.status(), reply.body());
        assertFalse(reply.body().contains("oid"), "no staff member is named");
    }

    @Test
    void everyStaffRoleAndTheProxyAndTheMetricsScraperAreDeniedTheLookup() {
        UUID oid = UUID.randomUUID();
        String discordId = discordUserId();
        givenLinkedStaff(oid, discordId, TestJwtIssuer.MODERATOR_GROUP);
        String path = "/discord/staff-links/" + discordId;

        assertEquals(403, get(path, this.jwt.viewer()).status(), "viewer");
        assertEquals(403, get(path, this.jwt.moderator()).status(), "moderator");
        assertEquals(403, get(path, this.jwt.admin()).status(), "admin");
        assertEquals(403, get(path, this.jwt.proxy()).status(), "proxy");
        assertEquals(403, get(path, this.jwt.metrics()).status(), "metrics");
        assertTrue(get(path, bot()).status() == 200, "the bot alone is allowed");
    }
}
