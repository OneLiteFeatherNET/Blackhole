package net.onelitefeather.blackhole.velocity.sentry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentryConfigTest {

    private static SentryConfig from(Map<String, String> environment, String pluginVersion) {
        return SentryConfig.fromEnvironment(environment::get, pluginVersion);
    }

    @Test
    void sentryStaysOffWhenNoDsnIsSet() {
        assertFalse(from(Map.of(), "2.0.0").isEnabled(), "without SENTRY_DSN nothing is reported");
    }

    @Test
    void sentryStaysOffWhenTheDsnIsBlank() {
        assertFalse(from(Map.of(SentryConfig.ENV_DSN, "   "), "2.0.0").isEnabled(), "a blank SENTRY_DSN counts as unset");
    }

    @Test
    void aDsnFromTheEnvironmentTurnsSentryOn() {
        SentryConfig config = from(Map.of(SentryConfig.ENV_DSN, " dsn-placeholder "), "2.0.0");

        assertTrue(config.isEnabled(), "a set SENTRY_DSN enables reporting");
        assertEquals("dsn-placeholder", config.getDsn(), "the DSN is read stripped of surrounding blanks");
    }

    @Test
    void theEnvironmentDefaultsToProd() {
        assertEquals("prod", from(Map.of(), "2.0.0").getEnvironment());
    }

    @Test
    void theEnvironmentComesFromTheVariable() {
        assertEquals("staging", from(Map.of(SentryConfig.ENV_ENVIRONMENT, "staging"), "2.0.0").getEnvironment());
    }

    @Test
    void aBlankEnvironmentKeepsTheDefault() {
        assertEquals("prod", from(Map.of(SentryConfig.ENV_ENVIRONMENT, " "), "2.0.0").getEnvironment());
    }

    @Test
    void theReleaseDefaultsToThePluginVersion() {
        assertEquals("2.0.0", from(Map.of(), "2.0.0").getRelease());
    }

    @Test
    void theReleaseVariableOverridesThePluginVersion() {
        assertEquals("custom-1", from(Map.of(SentryConfig.ENV_RELEASE, "custom-1"), "2.0.0").getRelease());
    }

    @Test
    void noReleaseIsSetWhenTheVersionIsUnknownAndNoVariableIsGiven() {
        assertNull(from(Map.of(), null).getRelease(), "an unknown plugin version must not invent a release name");
    }

    @Test
    void sendingPiiDefaultsToTrue() {
        assertTrue(from(Map.of(), "2.0.0").isSendDefaultPii(), "the maintainer's default is to send default PII");
    }

    @Test
    void sendingPiiCanBeSwitchedOff() {
        assertFalse(from(Map.of(SentryConfig.ENV_SEND_DEFAULT_PII, "false"), "2.0.0").isSendDefaultPii());
        assertFalse(from(Map.of(SentryConfig.ENV_SEND_DEFAULT_PII, "0"), "2.0.0").isSendDefaultPii());
    }

    @Test
    void aBlankSendDefaultPiiKeepsTheDefault() {
        assertTrue(from(Map.of(SentryConfig.ENV_SEND_DEFAULT_PII, ""), "2.0.0").isSendDefaultPii());
    }
}
