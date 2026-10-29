package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * The {@code intel} section of {@code config.json}: how the proxy reports player sessions to the backend.
 */
public final class IntelConfig {

    private static final int DEFAULT_REPORT_TIMEOUT_SECONDS = 2;

    /** How long one session report may take at the backend before it is given up. */
    private int reportTimeoutSeconds;

    public IntelConfig() {
        this.reportTimeoutSeconds = DEFAULT_REPORT_TIMEOUT_SECONDS;
    }

    /**
     * @return how long one session report may take; the default of 2 seconds if the setting is not positive
     */
    public @NotNull Duration getReportTimeout() {
        return Duration.ofSeconds(this.reportTimeoutSeconds > 0 ? this.reportTimeoutSeconds : DEFAULT_REPORT_TIMEOUT_SECONDS);
    }
}
