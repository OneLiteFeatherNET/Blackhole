package net.onelitefeather.blackhole.discord.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenSettingsTest {

    private static TokenSettings settings(String environments, String tokenUrl) {
        Map<String, String> env = new HashMap<>();
        if (environments != null) {
            env.put(TokenSettings.ENV_ENVIRONMENTS, environments);
        }
        return TokenSettings.of("", "client", "fake-secret", "x", tokenUrl, env::get);
    }

    @Test
    void anHttpTokenUrlIsRefusedWhenNoEnvironmentIsSet() {
        TokenSettings settings = settings(null, "http://localhost:18090/blackhole/token");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, settings::tokenUri,
                "the secret must not travel over http outside dev");

        assertTrue(e.getMessage().contains("BLACKHOLE_DISCORD_TOKEN_URL"), "names the setting: " + e.getMessage());
    }

    @Test
    void anHttpTokenUrlIsRefusedInProduction() {
        assertThrows(IllegalArgumentException.class, settings("prod", "http://localhost:18090/t")::tokenUri,
                "prod requires https");
    }

    @Test
    void anHttpTokenUrlToALoopbackHostIsAllowedInDevAndTest() {
        assertEquals(URI.create("http://localhost:18090/t"), settings("dev", "http://localhost:18090/t").tokenUri(), "dev");
        assertEquals(URI.create("http://127.0.0.1:18090/t"), settings("test", "http://127.0.0.1:18090/t").tokenUri(), "test");
        assertEquals(URI.create("http://[::1]:18090/t"), settings("prod,dev", "http://[::1]:18090/t").tokenUri(), "ipv6");
    }

    @Test
    void anHttpTokenUrlToARemoteHostIsRefusedEvenInDev() {
        assertThrows(IllegalArgumentException.class, settings("dev", "http://idp.example.com/token")::tokenUri,
                "only loopback may use http");
    }

    @Test
    void anHttpsTokenUrlIsAllowedInEveryEnvironment() {
        assertEquals(URI.create("https://idp.example.com/token"), settings("prod", "https://idp.example.com/token").tokenUri(),
                "https is always fine");
    }
}
