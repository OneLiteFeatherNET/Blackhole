package net.onelitefeather.blackhole.velocity.sentry;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Reports the plugin's own errors (failed initialisation, failed commands) to Sentry. Velocity's Log4j2
 * configuration is shared by the whole proxy, so no log appender is added there; the failures are
 * reported explicitly from the places that own them. Every method is safe to call while reporting is off.
 */
public final class SentryReporter {

    private final boolean enabled;
    private final SentryScrubber scrubber;

    private SentryReporter(boolean enabled, SentryScrubber scrubber) {
        this.enabled = enabled;
        this.scrubber = scrubber;
    }

    /**
     * Starts Sentry if a DSN is configured. A failure here is logged and reporting stays off; the plugin
     * always starts.
     *
     * @param config the settings from the environment
     * @param logger the plugin's logger, which never receives the DSN
     * @return the reporter, off unless the DSN is set and Sentry started
     */
    public static @NotNull SentryReporter start(@NotNull SentryConfig config, @NotNull Logger logger) {
        SentryScrubber scrubber = new SentryScrubber();
        if (!config.isEnabled()) {
            return new SentryReporter(false, scrubber);
        }
        try {
            Sentry.init(options(config, scrubber));
            logger.info("Plugin errors are reported to Sentry (environment {})", config.getEnvironment());
            return new SentryReporter(true, scrubber);
        } catch (RuntimeException | LinkageError e) {
            // The class name only: the message of a bad DSN may repeat the DSN.
            logger.warn("Sentry could not be started, plugin errors are not reported: {}", e.getClass().getName());
            return new SentryReporter(false, scrubber);
        }
    }

    /**
     * @param config   the settings from the environment
     * @param scrubber the hook that removes Ban IDs and credentials from every event
     * @return the SDK options: only the environment configures Sentry, the JVM's uncaught handler is left alone
     */
    static @NotNull SentryOptions options(@NotNull SentryConfig config, @NotNull SentryScrubber scrubber) {
        SentryOptions options = new SentryOptions();
        options.setDsn(config.getDsn());
        options.setEnvironment(config.getEnvironment());
        options.setRelease(config.getRelease());
        options.setSendDefaultPii(config.isSendDefaultPii());
        options.setEnableExternalConfiguration(false);
        options.setEnableUncaughtExceptionHandler(false);
        options.setAttachServerName(false);
        options.setBeforeSend(scrubber::scrub);
        return options;
    }

    /** @return whether Sentry was started and errors are sent */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @param value a credential that must never be sent, e.g. the client secret; blank values are ignored
     */
    public void registerSecret(String value) {
        scrubber.addSecret(value);
    }

    /**
     * Adds a tag to every later event, e.g. the proxy id once it is known.
     *
     * @param key   the tag name
     * @param value the tag value; it passes through the scrubber as well
     */
    public void tag(String key, String value) {
        if (!enabled || value == null) {
            return;
        }
        try {
            Sentry.setTag(key, scrubber.text(value));
        } catch (RuntimeException e) {
            // Reporting must never break the plugin.
        }
    }

    /**
     * Reports a failure.
     *
     * @param error the failure; its message passes through the scrubber before it is sent
     * @param stage where it happened, e.g. {@code proxy-initialisation}, shown as a tag
     */
    public void capture(@NotNull Throwable error, @NotNull String stage) {
        if (!enabled) {
            return;
        }
        try {
            Sentry.captureException(error, scope -> scope.setTag("blackhole.stage", stage));
        } catch (RuntimeException e) {
            // Reporting must never break the plugin.
        }
    }

    /** Flushes pending events and stops Sentry; a no-op while reporting is off. */
    public void close() {
        if (!enabled) {
            return;
        }
        try {
            Sentry.close();
        } catch (RuntimeException e) {
            // Shutdown must go on even if the flush fails.
        }
    }
}
