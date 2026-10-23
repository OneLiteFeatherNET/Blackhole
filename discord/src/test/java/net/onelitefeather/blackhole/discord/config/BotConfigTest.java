package net.onelitefeather.blackhole.discord.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotConfigTest {

    private static Map<String, String> complete() {
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_TOKEN", "bot-token-secret");
        env.put("DISCORD_GUILD_ID", "111111111111111111");
        env.put("DISCORD_TEAM_CHANNEL_ID", "222222222222222222");
        env.put("DISCORD_STAFF_ROLE_ID", "333333333333333333");
        env.put("BLACKHOLE_BACKEND_URL", "http://backend:8080/");
        env.put("BLACKHOLE_PANEL_URL", "https://panel.example.org//");
        env.put("BLACKHOLE_DISCORD_TENANT_ID", "tenant-1");
        env.put("BLACKHOLE_DISCORD_CLIENT_ID", "client-1");
        env.put("BLACKHOLE_DISCORD_CLIENT_SECRET", "client-secret");
        env.put("BLACKHOLE_DISCORD_AUTH_SCOPE", "api://api-1/.default");
        return env;
    }

    private static List<String> problems(Map<String, String> env) {
        return assertThrows(BotConfig.ConfigException.class, () -> BotConfig.fromEnvironment(env::get)).problems();
    }

    @Test
    void aCompleteEnvironmentIsParsedAndUrlsLoseTheirTrailingSlashes() throws Exception {
        BotConfig config = BotConfig.fromEnvironment(complete()::get);

        assertEquals(111111111111111111L, config.guildId(), "guild");
        assertEquals(222222222222222222L, config.teamChannelId(), "team channel");
        assertEquals(333333333333333333L, config.staffRoleId(), "staff role");
        assertEquals("http://backend:8080", config.backendUrl(), "backend url");
        assertEquals("https://panel.example.org", config.panelUrl(), "panel url");
        assertEquals(8082, config.healthPort(), "default health port");
    }

    @Test
    void everyMissingValueIsReportedAtOnce() {
        List<String> problems = problems(new HashMap<>());

        for (String name : List.of("DISCORD_TOKEN", "DISCORD_GUILD_ID", "DISCORD_TEAM_CHANNEL_ID", "DISCORD_STAFF_ROLE_ID",
                "BLACKHOLE_BACKEND_URL", "BLACKHOLE_PANEL_URL", "BLACKHOLE_DISCORD_CLIENT_ID",
                "BLACKHOLE_DISCORD_CLIENT_SECRET", "BLACKHOLE_DISCORD_AUTH_SCOPE")) {
            assertTrue(problems.stream().anyMatch(problem -> problem.contains(name)), name + " is named: " + problems);
        }
    }

    @Test
    void aMalformedIdAndUrlAreRefused() {
        Map<String, String> env = complete();
        env.put("DISCORD_GUILD_ID", "not-a-number");
        env.put("BLACKHOLE_BACKEND_URL", "ftp://backend");

        List<String> problems = problems(env);

        assertTrue(problems.stream().anyMatch(p -> p.startsWith("DISCORD_GUILD_ID")), "guild id: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("BLACKHOLE_BACKEND_URL")), "backend url: " + problems);
    }

    @Test
    void aTokenUrlOverrideReplacesTheTenant() throws Exception {
        Map<String, String> env = complete();
        env.remove("BLACKHOLE_DISCORD_TENANT_ID");
        env.put("BLACKHOLE_DISCORD_TOKEN_URL", "http://localhost:18090/blackhole/token");

        BotConfig config = BotConfig.fromEnvironment(env::get);

        assertEquals("http://localhost:18090/blackhole/token", config.auth().tokenUri().toString(), "dev token endpoint");
    }

    @Test
    void aHealthPortOfZeroTurnsTheEndpointOffAndABadOneIsRefused() throws Exception {
        Map<String, String> env = complete();
        env.put("DISCORD_HEALTH_PORT", "0");
        assertEquals(0, BotConfig.fromEnvironment(env::get).healthPort(), "off");

        env.put("DISCORD_HEALTH_PORT", "70000");
        assertTrue(problems(env).stream().anyMatch(p -> p.startsWith("DISCORD_HEALTH_PORT")), "out of range");
    }

    @Test
    void theStringFormNeverContainsTheBotTokenOrTheClientSecret() throws Exception {
        String text = BotConfig.fromEnvironment(complete()::get).toString();

        assertFalse(text.contains("bot-token-secret"), "bot token: " + text);
        assertFalse(text.contains("client-secret"), "client secret: " + text);
    }
}
