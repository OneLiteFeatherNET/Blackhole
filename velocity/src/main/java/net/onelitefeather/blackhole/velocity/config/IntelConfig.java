package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * The {@code intel} section of {@code config.json}: how the proxy reports player sessions to the backend.
 */
public final class IntelConfig {

    private static final int DEFAULT_REPORT_TIMEOUT_SECONDS = 2;
    private static final int DEFAULT_LOGIN_CHECK_TIMEOUT_MILLIS = 1000;

    /** How long one session report may take at the backend before it is given up. */
    private int reportTimeoutSeconds;

    /** How long a login may wait for the backend's VPN verdict; past it the login is allowed. */
    private int loginCheckTimeoutMillis;

    public IntelConfig() {
        this.reportTimeoutSeconds = DEFAULT_REPORT_TIMEOUT_SECONDS;
        this.loginCheckTimeoutMillis = DEFAULT_LOGIN_CHECK_TIMEOUT_MILLIS;
    }

    /**
     * @return how long one session report may take; the default of 2 seconds if the setting is not positive
     */
    public @NotNull Duration getReportTimeout() {
        return Duration.ofSeconds(this.reportTimeoutSeconds > 0 ? this.reportTimeoutSeconds : DEFAULT_REPORT_TIMEOUT_SECONDS);
    }

    /**
     * @return how long a login waits for the VPN verdict; the default of 1 second if the setting is not positive
     */
    public @NotNull Duration getLoginCheckTimeout() {
        return Duration.ofMillis(this.loginCheckTimeoutMillis > 0 ? this.loginCheckTimeoutMillis : DEFAULT_LOGIN_CHECK_TIMEOUT_MILLIS);
    }
}
