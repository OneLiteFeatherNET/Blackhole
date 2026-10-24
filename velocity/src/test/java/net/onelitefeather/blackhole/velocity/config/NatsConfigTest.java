package net.onelitefeather.blackhole.velocity.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NatsConfigTest {

    private static BlackholeConfig parse(String json) {
        return new Gson().fromJson(json, BlackholeConfig.class);
    }

    @Test
    void theDefaultsPointAtTheWebSocketRouteWithoutCredentials() {
        NatsConfig config = new BlackholeConfig().getNats();

        assertEquals("wss://nats.onelitefeather.dev", config.getUrl());
        assertEquals("", config.getCredsFile());
        assertEquals(Duration.ofSeconds(60), config.getCacheMaxAge());
    }

    @Test
    void aConfigWrittenBeforeTheSectionExistedUsesTheDefaults() {
        assertEquals("wss://nats.onelitefeather.dev", parse("{}").getNats().getUrl());
    }

    @Test
    void theFileValuesAreRead() {
        NatsConfig config = parse("{\"nats\":{\"url\":\"wss://other\",\"credsFile\":\"/etc/blackhole.creds\",\"cacheMaxAge\":\"PT30S\"}}").getNats();

        assertEquals("wss://other", config.getUrl());
        assertEquals("/etc/blackhole.creds", config.getCredsFile());
        assertEquals(Duration.ofSeconds(30), config.getCacheMaxAge());
    }

    @Test
    void aLegacyRedisUriDoesNotDisturbTheNatsSection() {
        assertEquals("wss://nats.onelitefeather.dev", parse("{\"redisUri\":\"redis://x\"}").getNats().getUrl());
    }

    @Test
    void theEnvironmentOverridesTheFile() {
        NatsConfig config = parse("{\"nats\":{\"url\":\"wss://file\",\"credsFile\":\"/file.creds\"}}").getNats()
                .withEnvironment(Map.of(NatsConfig.ENV_URL, "wss://env", NatsConfig.ENV_CREDS_FILE, "/env.creds")::get);

        assertEquals("wss://env", config.getUrl());
        assertEquals("/env.creds", config.getCredsFile());
    }

    @Test
    void blankEnvironmentValuesKeepTheFile() {
        NatsConfig config = parse("{\"nats\":{\"url\":\"wss://file\"}}").getNats()
                .withEnvironment(Map.of(NatsConfig.ENV_URL, " ")::get);

        assertEquals("wss://file", config.getUrl());
    }

    @Test
    void anUnreadableOrTooSmallMaximumAgeFallsBackToSixtySeconds() {
        assertEquals(Duration.ofSeconds(60), parse("{\"nats\":{\"cacheMaxAge\":\"soon\"}}").getNats().getCacheMaxAge());
        assertEquals(Duration.ofSeconds(60), parse("{\"nats\":{\"cacheMaxAge\":\"PT0S\"}}").getNats().getCacheMaxAge());
    }
}
