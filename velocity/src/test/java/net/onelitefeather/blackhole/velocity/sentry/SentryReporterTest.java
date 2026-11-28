package net.onelitefeather.blackhole.velocity.sentry;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SentryReporterTest {

    /** A stand-in: the real DSN only ever comes from the environment, never from the repository. */
    private static final String DSN = "dsn-placeholder";

    private static SentryConfig configured(Map<String, String> environment) {
        return SentryConfig.fromEnvironment(environment::get, "2.0.0");
    }

    @Test
    void withoutADsnSentryStaysOff() {
        SentryReporter reporter = SentryReporter.start(configured(Map.of()), NOPLogger.NOP_LOGGER);

        assertFalse(reporter.enabled(), "no DSN means nothing is initialised and nothing is reported");
    }

    @Test
    void capturingWhileOffDoesNothingAndNeverThrows() {
        SentryReporter reporter = SentryReporter.start(configured(Map.of()), NOPLogger.NOP_LOGGER);

        assertDoesNotThrow(() -> reporter.capture(new IllegalStateException("boom"), "init"),
                "reporting must never break the plugin, even when it is off");
        assertDoesNotThrow(reporter::close, "closing an off reporter is a no-op");
    }

    @Test
    void theOptionsCarryTheEnvironmentReleaseAndPiiSetting() {
        SentryOptions options = SentryReporter.options(configured(Map.of(SentryConfig.ENV_DSN, DSN,
                SentryConfig.ENV_ENVIRONMENT, "staging", SentryConfig.ENV_SEND_DEFAULT_PII, "false")), new SentryScrubber());

        assertEquals(DSN, options.getDsn(), "the DSN from the environment is the only target");
        assertEquals("staging", options.getEnvironment(), "the environment name is passed through");
        assertEquals("2.0.0", options.getRelease(), "the release defaults to the plugin version");
        assertFalse(options.isSendDefaultPii(), "the PII switch is passed through");
    }

    @Test
    void theOptionsDoNotReadOtherConfigurationOrTakeOverTheJvmHandler() {
        SentryOptions options = SentryReporter.options(configured(Map.of(SentryConfig.ENV_DSN, DSN)), new SentryScrubber());

        assertFalse(options.isEnableExternalConfiguration(), "only the BLACKHOLE environment decides, not sentry.properties");
        assertFalse(options.isEnableUncaughtExceptionHandler(), "the JVM's uncaught exception handler stays as the proxy set it");
    }

    @Test
    void theBeforeSendHookScrubsTheEventBeforeItIsSent() {
        SentryOptions options = SentryReporter.options(configured(Map.of(SentryConfig.ENV_DSN, DSN)), new SentryScrubber());
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage("punish of #AB12CD34 failed");
        message.setFormatted("punish of #AB12CD34 failed");
        event.setMessage(message);

        SentryEvent sent = options.getBeforeSend().execute(event, new Hint());

        assertNotNull(sent, "the hook keeps the event");
        assertEquals("punish of [ban-id] failed", sent.getMessage().getFormatted(), "the Ban ID is gone when the event leaves the proxy");
    }
}
