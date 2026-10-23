package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The optional CloudNet integration ({@code cloudnet} in {@code config.json}). With {@code auto}
 * (the default) the plugin uses CloudNet when it runs as a CloudNet service; {@code off} never
 * probes for it. Any other value counts as {@code auto}.
 *
 * <p>Catalogue reporting: {@code proxyId} overrides the id the proxy reports under (default: its
 * CloudNet service name), {@code reportDebounce} is how long changes are coalesced (ISO-8601,
 * default {@code PT5S}) and {@code heartbeat} the keep-alive period (default {@code PT5M}).</p>
 */
public final class CloudNetConfig {

    public static final String ENV_INTEGRATION = "BLACKHOLE_CLOUDNET_INTEGRATION";

    static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(5);
    static final Duration DEFAULT_HEARTBEAT = Duration.ofMinutes(5);
    private static final Duration MINIMUM = Duration.ofSeconds(1);

    private String integration;
    private String proxyId;
    private String reportDebounce;
    private String heartbeat;

    public CloudNetConfig() {
        this.integration = "auto";
    }

    /**
     * @return {@code off} or {@code auto}
     */
    public @NotNull String getIntegration() {
        return integration != null && integration.strip().equalsIgnoreCase("off") ? "off" : "auto";
    }

    /**
     * @return the configured proxy id, or empty to use the CloudNet service name
     */
    public @NotNull Optional<String> getProxyId() {
        return proxyId == null || proxyId.isBlank() ? Optional.empty() : Optional.of(proxyId.strip());
    }

    public @NotNull Duration getReportDebounce() {
        return parse(reportDebounce, DEFAULT_DEBOUNCE);
    }

    public @NotNull Duration getHeartbeat() {
        return parse(heartbeat, DEFAULT_HEARTBEAT);
    }

    /** An unreadable or sub-second value falls back to the default so a typo cannot make the proxy spin. */
    private static Duration parse(String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            Duration parsed = Duration.parse(value.strip());
            return parsed.compareTo(MINIMUM) < 0 ? fallback : parsed;
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    /**
     * @return the setting with the environment variable {@value #ENV_INTEGRATION} applied if it is set
     */
    public @NotNull CloudNetConfig withEnvironment(@NotNull Function<String, String> env) {
        String override = env.apply(ENV_INTEGRATION);
        CloudNetConfig result = new CloudNetConfig();
        result.proxyId = proxyId;
        result.reportDebounce = reportDebounce;
        result.heartbeat = heartbeat;
        result.integration = override == null || override.isBlank() ? getIntegration() : override.strip().toLowerCase(Locale.ROOT);
        return result;
    }
}
