package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An app-only token is granted a machine role only when its {@code azp} (the client that obtained
 * it) is on the allow-list of that role. The test application allow-lists the fake client ids of
 * {@link TestJwtIssuer}.
 */
@Tag("integration")
class MachineClientBindingTest extends ApiIntegrationTest {

    private static void assertPassesSecurity(Reply reply, String what) {
        assertTrue(reply.status() != 401 && reply.status() != 403, what + " must pass security, got " + reply.status());
    }

    @Test
    void anAllowListedClientWithTheProxyRoleIsTheProxy() {
        String token = this.jwt.appOnly().azp(TestJwtIssuer.PROXY_CLIENT).roles(TestJwtIssuer.PROXY_ROLE).sign();

        assertPassesSecurity(post("/punishment/apply", Map.of(), token), "the allow-listed proxy client");
    }

    @Test
    void anUnlistedClientWithTheProxyRoleIsRefused() {
        String token = this.jwt.appOnly().azp(TestJwtIssuer.UNLISTED_CLIENT).roles(TestJwtIssuer.PROXY_ROLE).sign();

        assertEquals(403, post("/punishment/apply", Map.of(), token).status(), "proxy role from an unlisted client");
    }

    @Test
    void aProxyRoleTokenWithoutAzpIsRefused() {
        String token = this.jwt.appOnly().roles(TestJwtIssuer.PROXY_ROLE).sign();

        assertEquals(403, post("/punishment/apply", Map.of(), token).status(), "proxy role without an azp");
    }

    @Test
    void theAppidClaimDoesNotCountAsTheClient() {
        String token = this.jwt.appOnly().claim("appid", TestJwtIssuer.PROXY_CLIENT).roles(TestJwtIssuer.PROXY_ROLE).sign();

        assertEquals(403, post("/punishment/apply", Map.of(), token).status(), "only azp binds the client");
    }

    @Test
    void anAllowListedClientWithTheMetricsRoleMayScrape() {
        String token = this.jwt.appOnly().azp(TestJwtIssuer.METRICS_CLIENT).roles(TestJwtIssuer.METRICS_ROLE).sign();

        assertPassesSecurity(get("/prometheus", token), "the allow-listed metrics client");
    }

    @Test
    void anUnlistedClientWithTheMetricsRoleIsRefused() {
        String token = this.jwt.appOnly().azp(TestJwtIssuer.UNLISTED_CLIENT).roles(TestJwtIssuer.METRICS_ROLE).sign();

        assertEquals(403, get("/prometheus", token).status(), "metrics role from an unlisted client");
    }

    @Test
    void anAllowListedClientWithTheDiscordRoleIsTheBot() {
        String token = this.jwt.appOnly().azp(TestJwtIssuer.DISCORD_CLIENT).roles(TestJwtIssuer.DISCORD_ROLE).sign();

        assertPassesSecurity(get("/discord/tickets/unknown", token), "the allow-listed discord client");
    }

    @Test
    void anUnlistedClientWithTheDiscordRoleIsRefused() {
        String token = this.jwt.appOnly().azp(TestJwtIssuer.UNLISTED_CLIENT).roles(TestJwtIssuer.DISCORD_ROLE).sign();

        assertEquals(403, get("/discord/tickets/unknown", token).status(), "discord role from an unlisted client");
    }

    @Test
    void aClientListedForProxyCannotUseTheDiscordRole() {
        String token = this.jwt.appOnly().azp(TestJwtIssuer.PROXY_CLIENT).roles(TestJwtIssuer.DISCORD_ROLE).sign();

        assertEquals(403, get("/discord/tickets/unknown", token).status(), "allow-lists are per role");
    }
}
