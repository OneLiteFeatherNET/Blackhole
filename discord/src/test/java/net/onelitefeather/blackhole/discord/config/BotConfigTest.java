package net.onelitefeather.blackhole.discord.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
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
        env.put("RABBITMQ_HOST", "rabbit");
        env.put("RABBITMQ_USER", "guest");
        env.put("RABBITMQ_PASSWORD", "rabbit-secret");
        env.put("DISCORD_TICKET_CATEGORY_ID", "444444444444444444");
        env.put("DISCORD_ARCHIVE_CATEGORY_ID", "555555555555555555");
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
        assertEquals(new BotConfig.RabbitSettings("rabbit", 5672, "guest", "rabbit-secret", "/"), config.rabbit(), "broker with defaults");
    }

    @Test
    void everyMissingValueIsReportedAtOnce() {
        List<String> problems = problems(new HashMap<>());

        for (String name : List.of("DISCORD_TOKEN", "DISCORD_GUILD_ID", "DISCORD_TEAM_CHANNEL_ID", "DISCORD_STAFF_ROLE_ID",
                "BLACKHOLE_BACKEND_URL", "BLACKHOLE_PANEL_URL", "BLACKHOLE_DISCORD_CLIENT_ID",
                "BLACKHOLE_DISCORD_CLIENT_SECRET", "BLACKHOLE_DISCORD_AUTH_SCOPE", "RABBITMQ_HOST", "RABBITMQ_USER", "RABBITMQ_PASSWORD",
                "DISCORD_TICKET_CATEGORY_ID", "DISCORD_ARCHIVE_CATEGORY_ID")) {
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
        assertFalse(text.contains("rabbit-secret"), "broker password: " + text);
    }

    @Test
    void theTicketSettingsHaveTheDocumentedDefaults() throws Exception {
        BotConfig.TicketSettings tickets = BotConfig.fromEnvironment(complete()::get).tickets();

        assertEquals(444444444444444444L, tickets.ticketCategoryId(), "ticket category");
        assertEquals(555555555555555555L, tickets.archiveCategoryId(), "archive category");
        assertEquals(0L, tickets.appealChannelId(), "no appeal channel by default");
        assertEquals(Duration.ofDays(30), tickets.deleteAfter(), "archive deletion after 30 days");
        assertEquals(Duration.ofSeconds(30), tickets.syncInterval(), "cursor poll");
        assertEquals(Duration.ofMinutes(5), tickets.reconcileInterval(), "access reconcile");
    }

    @Test
    void theTicketSettingsCanBeOverridden() throws Exception {
        Map<String, String> env = complete();
        env.put("DISCORD_APPEAL_CHANNEL_ID", "666666666666666666");
        env.put("DISCORD_ARCHIVE_DELETE_AFTER", "PT0S");
        env.put("DISCORD_SYNC_INTERVAL", "PT10S");
        env.put("DISCORD_STAFF_RECONCILE_INTERVAL", "PT1M");

        BotConfig.TicketSettings tickets = BotConfig.fromEnvironment(env::get).tickets();

        assertEquals(666666666666666666L, tickets.appealChannelId(), "appeal channel");
        assertEquals(Duration.ZERO, tickets.deleteAfter(), "zero keeps archived channels");
        assertEquals(Duration.ofSeconds(10), tickets.syncInterval(), "sync interval");
        assertEquals(Duration.ofMinutes(1), tickets.reconcileInterval(), "reconcile interval");
    }

    @Test
    void sameCategoryTwiceAndBadDurationsAreRefused() {
        Map<String, String> env = complete();
        env.put("DISCORD_ARCHIVE_CATEGORY_ID", "444444444444444444");
        env.put("DISCORD_ARCHIVE_DELETE_AFTER", "-P1D");
        env.put("DISCORD_SYNC_INTERVAL", "30 seconds");

        List<String> problems = problems(env);

        assertTrue(problems.stream().anyMatch(p -> p.contains("must be different categories")), "categories: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("DISCORD_ARCHIVE_DELETE_AFTER")), "negative delay: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("DISCORD_SYNC_INTERVAL")), "not ISO-8601: " + problems);
    }

    @Test
    void aSyncIntervalBelowFiveSecondsIsRefused() {
        Map<String, String> env = complete();
        env.put("DISCORD_SYNC_INTERVAL", "PT1S");

        assertTrue(problems(env).stream().anyMatch(p -> p.startsWith("DISCORD_SYNC_INTERVAL")), "too fast a poll hammers the backend");
    }
}
