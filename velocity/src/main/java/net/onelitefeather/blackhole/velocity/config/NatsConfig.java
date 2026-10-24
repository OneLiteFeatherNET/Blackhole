package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/**
 * Where the proxy listens for the backend's events. The proxy only ever subscribes; the server rejects
 * any publish of its credential. Both connection values can be overridden by environment variables, see
 * {@link #withEnvironment(Function)}.
 */
public final class NatsConfig {

    public static final String ENV_URL = "NATS_URL";
    public static final String ENV_CREDS_FILE = "NATS_CREDS_FILE";

    public static final String DEFAULT_URL = "wss://nats.onelitefeather.dev";
    static final Duration DEFAULT_CACHE_MAX_AGE = Duration.ofSeconds(60);
    private static final Duration MINIMUM_CACHE_MAX_AGE = Duration.ofSeconds(1);

    /** Server URL(s), comma separated. {@code wss://} for a proxy outside the cluster. */
    private String url = DEFAULT_URL;

    /** Path of the {@code .creds} file of the proxy's NATS user; empty connects without credentials. */
    private String credsFile = "";

    /**
     * How long a cached player state may be trusted while connected, an ISO-8601 duration. A lost event
     * heals itself after at most this long. Default {@code PT60S}.
     */
    private String cacheMaxAge = "PT60S";

    public NatsConfig() {
    }

    private NatsConfig(String url, String credsFile, String cacheMaxAge) {
        this.url = url;
        this.credsFile = credsFile;
        this.cacheMaxAge = cacheMaxAge;
    }

    public @NotNull String getUrl() {
        return url == null || url.isBlank() ? DEFAULT_URL : url.strip();
    }

    /** @return the credentials file, empty if none is configured */
    public @NotNull String getCredsFile() {
        return credsFile == null ? "" : credsFile.strip();
    }

    /** An unreadable or sub-second value falls back to the default so a typo cannot disable the cache bound. */
    public @NotNull Duration getCacheMaxAge() {
        if (cacheMaxAge == null || cacheMaxAge.isBlank()) {
            return DEFAULT_CACHE_MAX_AGE;
        }
        try {
            Duration parsed = Duration.parse(cacheMaxAge.strip());
            return parsed.compareTo(MINIMUM_CACHE_MAX_AGE) < 0 ? DEFAULT_CACHE_MAX_AGE : parsed;
        } catch (DateTimeParseException e) {
            return DEFAULT_CACHE_MAX_AGE;
        }
    }

    /**
     * @param env looks up an environment variable, null if unset
     * @return a copy in which each environment variable that is set and not blank replaces the file value
     */
    public @NotNull NatsConfig withEnvironment(@NotNull Function<String, String> env) {
        String envUrl = env.apply(ENV_URL);
        String envCreds = env.apply(ENV_CREDS_FILE);
        return new NatsConfig(
                envUrl == null || envUrl.isBlank() ? getUrl() : envUrl.strip(),
                envCreds == null || envCreds.isBlank() ? getCredsFile() : envCreds.strip(),
                cacheMaxAge);
    }
}
