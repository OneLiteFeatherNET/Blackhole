package net.onelitefeather.blackhole.backend.discord;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.security.TestJwtIssuer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Discord account link over HTTP against the real database: self-signed tokens, the role of each
 * route and the whole flows (staff code from the panel, player code from the proxy, confirmation by
 * the bot). Each test uses its own random ids, so tests share nothing in the class-wide database.
 */
@Tag("integration")
class DiscordLinkApiIntegrationTest extends ApiIntegrationTest {

    private static String discordUserId() {
        return Long.toString(100_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(900_000_000_000_000_000L));
    }

    private static String owner() {
        return (UUID.randomUUID().toString() + UUID.randomUUID() + UUID.randomUUID() + UUID.randomUUID()).replace("-", "").substring(0, 128);
    }

    private String staff(UUID oid) {
        return this.jwt.delegated().claim("oid", oid.toString()).claim("name", "Staff " + oid.toString().substring(0, 8))
                .groups(TestJwtIssuer.VIEWER_GROUP).sign();
    }

    private String proxy() {
        return this.jwt.appOnly().azp(TestJwtIssuer.PROXY_CLIENT).claim("oid", UUID.randomUUID().toString()).roles(TestJwtIssuer.PROXY_ROLE).sign();
    }

    private String bot() {
        return this.jwt.appOnly().azp(TestJwtIssuer.DISCORD_CLIENT).claim("oid", UUID.randomUUID().toString()).roles(TestJwtIssuer.DISCORD_ROLE).sign();
    }

    private String staffCode(String token) {
        Reply reply = post("/staff-discord-link/code", null, token);
        assertEquals(200, reply.status(), reply.body());
        return (String) reply.json().get("code");
    }

    private String playerCode(String owner) {
        Reply reply = post("/player-discord-link/code", Map.of("owner", owner), proxy());
        assertEquals(200, reply.status(), reply.body());
        return (String) reply.json().get("code");
    }

    private Reply confirm(String code, String discordUserId) {
        return post("/discord/link/confirm", Map.of("code", code, "discordUserId", discordUserId), bot());
    }

    @Test
    void aStaffMemberLinksADiscordAccountWithACodeTheBotConfirms() {
        UUID oid = UUID.randomUUID();
        String token = staff(oid);
        String discord = discordUserId();

        Reply confirmed = confirm(staffCode(token), discord);

        assertEquals(200, confirmed.status(), confirmed.body());
        assertEquals("STAFF", confirmed.json().get("kind"), "a staff link");
        assertEquals(true, get("/staff-discord-link/me", token).json().get("linked"), "the panel sees it");
        assertFalse(get("/staff-discord-link/me", token).body().contains(discord), "the Discord id is never returned");
        Reply who = get("/discord/link/" + discord, bot());
        assertEquals(200, who.status(), who.body());
        assertEquals("STAFF", ((Map<?, ?>) ((List<?>) who.json().get("links")).getFirst()).get("kind"), "whoami knows the user");
    }

    @Test
    void aPlayerLinksADiscordAccountWithACodeFromTheProxy() {
        String owner = owner();
        String discord = discordUserId();

        Reply confirmed = confirm(playerCode(owner), discord);

        assertEquals(200, confirmed.status(), confirmed.body());
        assertEquals("PLAYER", confirmed.json().get("kind"), "a player link");
    }

    @Test
    void aWrongAndAUsedCodeAreRefusedAlikeWithInvalidCode() {
        String code = staffCode(staff(UUID.randomUUID()));
        confirm(code, discordUserId());

        Reply wrong = confirm("ABCD-EFGH", discordUserId());
        Reply reused = confirm(code, discordUserId());

        assertEquals(400, wrong.status(), wrong.body());
        assertEquals("INVALID_CODE", wrong.json().get("code"), "the uniform error");
        assertEquals(wrong.body(), reused.body(), "a used code looks like a wrong one");
    }

    @Test
    void aMalformedDiscordUserIdIsABadRequest() {
        assertEquals(400, post("/discord/link/confirm", Map.of("code", "ABCD-EFGH", "discordUserId", "x"), bot()).status(), "not a snowflake");
    }

    @Test
    void theBotUnlinksAUserAndTheStaffLinkIsGone() {
        String token = staff(UUID.randomUUID());
        String discord = discordUserId();
        confirm(staffCode(token), discord);

        assertEquals(200, delete("/discord/link/" + discord, bot()).status(), "unlinked");

        assertEquals(false, get("/staff-discord-link/me", token).json().get("linked"), "no longer linked");
        assertEquals(404, delete("/discord/link/" + discord, bot()).status(), "a second unlink finds nothing");
    }

    @Test
    void aStaffMemberCanUnlinkThemselvesInThePanel() {
        String token = staff(UUID.randomUUID());
        confirm(staffCode(token), discordUserId());

        assertEquals(200, delete("/staff-discord-link/me", token).status(), "unlinked");
        assertEquals(404, delete("/staff-discord-link/me", token).status(), "nothing to unlink twice");
    }

    @Test
    void anAppOnlyTokenCannotRequestAStaffCode() {
        assertEquals(403, post("/staff-discord-link/code", null, proxy()).status(), "service principal");
    }

    @Test
    void onlyTheProxyCanIssueAPlayerCode() {
        assertEquals(403, post("/player-discord-link/code", Map.of("owner", owner()), this.jwt.admin()).status(), "an admin is not the proxy");
        assertEquals(403, post("/player-discord-link/code", Map.of("owner", owner()), bot()).status(), "the bot is not the proxy");
        assertEquals(400, post("/player-discord-link/code", Map.of("owner", UUID.randomUUID().toString()), proxy()).status(), "a raw UUID is refused");
    }

    @Test
    void anAdminListsAndRemovesLinksAndNoDiscordIdIsReturned() {
        UUID oid = UUID.randomUUID();
        String discord = discordUserId();
        confirm(staffCode(staff(oid)), discord);

        Reply list = get("/admin/discord-link", this.jwt.admin());

        assertEquals(200, list.status(), list.body());
        assertFalse(list.body().contains(discord), "no Discord id in the admin list");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.json().get("items");
        Map<String, Object> item = items.stream().filter(i -> oid.toString().equals(i.get("subject"))).findFirst().orElseThrow();
        assertEquals(403, delete("/admin/discord-link/" + item.get("id"), this.jwt.moderator()).status(), "moderator");
        assertEquals(200, delete("/admin/discord-link/" + item.get("id"), this.jwt.admin()).status(), "admin");
        assertEquals(404, delete("/admin/discord-link/" + item.get("id"), this.jwt.admin()).status(), "already gone");
    }

    @Test
    void anonymizingAStaffMemberRemovesTheirDiscordLink() {
        UUID oid = UUID.randomUUID();
        String token = staff(oid);
        confirm(staffCode(token), discordUserId());

        assertEquals(200, post("/admin/staff-actor/" + oid + "/anonymize", null, this.jwt.admin()).status(), "anonymized");

        assertFalse((Boolean) get("/staff-discord-link/me", token).json().get("linked"), "the Discord link is gone");
    }

    @Test
    void humanTokensAndTheProxyCannotUseTheBotFacade() {
        assertEquals(403, get("/discord/link/" + discordUserId(), this.jwt.admin()).status(), "admin");
        assertEquals(403, get("/discord/link/" + discordUserId(), proxy()).status(), "proxy");
        assertEquals(403, post("/discord/link/confirm", Map.of("code", "ABCD-EFGH", "discordUserId", discordUserId()), this.jwt.admin()).status(), "admin confirm");
    }

    @Test
    void aDelegatedTokenCarryingTheDiscordRoleValueIsNotTheBot() {
        String token = this.jwt.delegated().groups(TestJwtIssuer.ADMIN_GROUP).roles(TestJwtIssuer.DISCORD_ROLE).sign();

        assertEquals(403, get("/discord/link/" + discordUserId(), token).status(), "the role is read only from app-only tokens");
    }

    @Test
    void theBotTokenIsDeniedEverywhereElse() {
        String token = bot();

        assertEquals(403, get("/case", token).status(), "case");
        assertEquals(403, get("/punishment", token).status(), "punishment");
        assertEquals(403, get("/section", token).status(), "section");
        assertEquals(403, post("/staff-link/code", null, token).status(), "staff link");
        assertEquals(403, get("/admin/discord-link", token).status(), "admin link list");
        assertTrue(get("/auth/me", token).status() < 500, "identity still answers");
    }
}
