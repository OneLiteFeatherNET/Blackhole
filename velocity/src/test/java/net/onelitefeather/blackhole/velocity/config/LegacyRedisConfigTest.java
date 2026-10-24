package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The proxy no longer reads Redis: a leftover redisUri is warned about once and ignored. */
class LegacyRedisConfigTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void aLegacyRedisUriIsIgnoredWithOneWarning(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"),
                "{\"baseUrl\":\"http://backend:8080\",\"redisUri\":\"redis://dragonfly:6379\"}");

        BlackholeConfig config = BlackholeConfig.load(file, warnings::add);

        assertEquals("http://backend:8080", config.getBaseUrl(), "the rest of the file is still read");
        assertEquals(1, warnings.size(), "exactly one warning");
        assertTrue(warnings.getFirst().contains("redisUri") && warnings.getFirst().contains("Redis"), warnings.getFirst());
    }

    @Test
    void theLegacyKeyIsNotRewrittenOrReplacedByADefault(@TempDir Path dir) throws IOException {
        String content = "{\"redisUri\":\"redis://dragonfly:6379\"}";
        Path file = Files.writeString(dir.resolve("config.json"), content);

        BlackholeConfig.load(file, warnings::add);

        assertEquals(content, Files.readString(file), "loading never touches the operator's file");
    }

    @Test
    void aFreshConfigFileHasNoRedisSettingAndNoWarning(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");

        BlackholeConfig config = BlackholeConfig.load(file, warnings::add);

        assertFalse(Files.readString(file).toLowerCase().contains("redis"), "no Redis default is written");
        assertTrue(Files.readString(file).contains("\"nats\""), "the NATS section is written instead");
        assertEquals("wss://nats.onelitefeather.dev", config.getNats().getUrl());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anExistingFileWithoutRedisDoesNotWarn(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"), "{\"baseUrl\":\"http://x\"}");

        BlackholeConfig.load(file, warnings::add);

        assertTrue(warnings.isEmpty());
    }

    @Test
    void noRedisClientIsOnTheClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.lettuce.core.RedisClient"),
                "the proxy enforces bans and mutes over HTTP and NATS only");
    }
}
