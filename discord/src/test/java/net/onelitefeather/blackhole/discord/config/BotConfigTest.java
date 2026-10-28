package net.onelitefeather.blackhole.discord.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        env.put("NATS_URL", "nats://nats:4222");
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
        assertEquals(new BotConfig.NatsSettings("nats://nats:4222", ""), config.nats(), "broker without credentials");
    }

    @Test
    void everyMissingValueIsReportedAtOnce() {
        List<String> problems = problems(new HashMap<>());

        for (String name : List.of("DISCORD_TOKEN", "DISCORD_GUILD_ID", "DISCORD_TEAM_CHANNEL_ID", "DISCORD_STAFF_ROLE_ID",
                "BLACKHOLE_BACKEND_URL", "BLACKHOLE_PANEL_URL", "BLACKHOLE_DISCORD_CLIENT_ID",
                "BLACKHOLE_DISCORD_CLIENT_SECRET", "BLACKHOLE_DISCORD_AUTH_SCOPE", "NATS_URL",
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
    void aValidAuditWebhookIsReadButNeverPrinted() throws Exception {
        Map<String, String> env = complete();
        env.put("DISCORD_AUDIT_WEBHOOK_URL", "https://discord.com/api/webhooks/123456789012345678/AUDIT-TOKEN-9");

        BotConfig config = BotConfig.fromEnvironment(env::get);

        assertEquals("https://discord.com/api/webhooks/123456789012345678/AUDIT-TOKEN-9", config.audit().webhookUrl(), "the webhook is set");
        assertFalse(config.toString().contains("AUDIT-TOKEN-9"), "the token is not printed by the configuration");
    }

    @Test
    void anUnsetAuditWebhookSwitchesTheAuditOff() throws Exception {
        BotConfig config = BotConfig.fromEnvironment(complete()::get);

        assertNull(config.audit().webhookUrl(), "no webhook, no audit posts");
    }

    @Test
    void aMalformedAuditWebhookStopsTheStartAndTheUrlIsNotPrinted() {
        Map<String, String> env = complete();
        env.put("DISCORD_AUDIT_WEBHOOK_URL", "http://example.test/hook-SECRET-7");

        BotConfig.ConfigException thrown = assertThrows(BotConfig.ConfigException.class, () -> BotConfig.fromEnvironment(env::get));

        assertTrue(thrown.getMessage().contains("DISCORD_AUDIT_WEBHOOK_URL"), "the variable is named: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("example.test") || thrown.getMessage().contains("SECRET-7"), "the value is not printed");
    }

    @Test
    void aTokenUrlOverrideReplacesTheTenant() throws Exception {
        Map<String, String> env = complete();
        env.remove("BLACKHOLE_DISCORD_TENANT_ID");
        env.put("MICRONAUT_ENVIRONMENTS", "dev");
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

    @Test
    void theStringFormHidesCredentialsInTheNatsUrl() throws Exception {
        Map<String, String> env = complete();
        env.put("NATS_URL", "nats://user:nats-secret@nats:4222");

        String text = BotConfig.fromEnvironment(env::get).toString();

        assertFalse(text.contains("nats-secret"), "url password: " + text);
        assertTrue(text.contains("nats:4222"), "the host stays visible: " + text);
    }

    @Test
    void theRabbitMqVariablesOfEarlierVersionsAreIgnored() throws Exception {
        Map<String, String> env = complete();
        env.put("RABBITMQ_HOST", "rabbit");
        env.put("RABBITMQ_USER", "guest");
        env.put("RABBITMQ_PASSWORD", "rabbit-secret");

        BotConfig config = BotConfig.fromEnvironment(env::get);

        assertFalse(config.toString().contains("rabbit"), "no trace of the old broker: " + config);
    }

    @Test
    void aMissingNatsUrlIsNamedAndTheCredentialsFileIsOptional() {
        Map<String, String> env = complete();
        env.remove("NATS_URL");

        List<String> problems = problems(env);

        assertEquals(List.of("NATS_URL is missing"), problems, "only the URL is required");
    }

    @Test
    void aNatsUrlWithAnUnknownSchemeIsRefused() {
        Map<String, String> env = complete();
        env.put("NATS_URL", "http://nats:4222");

        assertTrue(problems(env).stream().anyMatch(p -> p.startsWith("NATS_URL")), "scheme");
    }

    @Test
    void severalNatsServersAndWebSocketUrlsAreAccepted() throws Exception {
        Map<String, String> env = complete();
        env.put("NATS_URL", "nats://a:4222, wss://nats.example.org");

        assertEquals("nats://a:4222, wss://nats.example.org", BotConfig.fromEnvironment(env::get).nats().url());
    }

    @Test
    void aCredentialsFileMustBeReadable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        Map<String, String> env = complete();
        env.put("NATS_CREDS_FILE", directory.resolve("missing.creds").toString());
        assertTrue(problems(env).stream().anyMatch(p -> p.startsWith("NATS_CREDS_FILE")), "missing file");

        java.nio.file.Path creds = java.nio.file.Files.writeString(directory.resolve("bot.creds"), "x");
        env.put("NATS_CREDS_FILE", creds.toString());
        assertEquals(creds.toString(), BotConfig.fromEnvironment(env::get).nats().credsFile(), "readable file");
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

    @Test
    void leakScanningDefaultsToPublicChannels() throws Exception {
        BotConfig.LeakSettings leak = BotConfig.fromEnvironment(complete()::get).leak();

        assertEquals(BotConfig.LeakMode.PUBLIC, leak.mode(), "public by default");
        assertTrue(leak.channelIds().isEmpty(), "no list");
    }

    @Test
    void listModeTakesTheConfiguredChannelsAndOffNeedsNone() throws Exception {
        Map<String, String> env = complete();
        env.put("DISCORD_LEAK_SCAN", "list");
        env.put("DISCORD_LEAK_SCAN_CHANNELS", "777777777777777777, 888888888888888888");

        BotConfig.LeakSettings list = BotConfig.fromEnvironment(env::get).leak();
        env.put("DISCORD_LEAK_SCAN", "OFF");
        env.remove("DISCORD_LEAK_SCAN_CHANNELS");
        BotConfig.LeakSettings off = BotConfig.fromEnvironment(env::get).leak();

        assertEquals(BotConfig.LeakMode.LIST, list.mode(), "case-insensitive mode");
        assertEquals(java.util.Set.of(777777777777777777L, 888888888888888888L), list.channelIds(), "both channels");
        assertEquals(BotConfig.LeakMode.OFF, off.mode(), "off");
    }

    @Test
    void listModeWithoutChannelsAnUnknownModeAndAMalformedIdAreRefused() {
        Map<String, String> noChannels = complete();
        noChannels.put("DISCORD_LEAK_SCAN", "LIST");
        Map<String, String> unknown = complete();
        unknown.put("DISCORD_LEAK_SCAN", "EVERYTHING");
        Map<String, String> malformed = complete();
        malformed.put("DISCORD_LEAK_SCAN", "LIST");
        malformed.put("DISCORD_LEAK_SCAN_CHANNELS", "general");

        assertTrue(problems(noChannels).stream().anyMatch(p -> p.startsWith("DISCORD_LEAK_SCAN_CHANNELS is missing")), "list needs channels");
        assertTrue(problems(unknown).stream().anyMatch(p -> p.startsWith("DISCORD_LEAK_SCAN must be")), "unknown mode");
        assertTrue(problems(malformed).stream().anyMatch(p -> p.startsWith("DISCORD_LEAK_SCAN_CHANNELS must be")), "malformed id");
    }
}
