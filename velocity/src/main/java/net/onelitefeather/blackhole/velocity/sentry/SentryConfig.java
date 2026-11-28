package net.onelitefeather.blackhole.velocity.sentry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Where the proxy reports its own errors. Environment only, never {@code config.json}, so the DSN is
 * not committed next to the rest of the file. Without {@link #ENV_DSN} reporting stays off.
 */
public final class SentryConfig {

    public static final String ENV_DSN = "SENTRY_DSN";
    public static final String ENV_ENVIRONMENT = "SENTRY_ENVIRONMENT";
    public static final String ENV_RELEASE = "SENTRY_RELEASE";
    public static final String ENV_SEND_DEFAULT_PII = "SENTRY_SEND_DEFAULT_PII";

    static final String DEFAULT_ENVIRONMENT = "prod";
    private static final Set<String> FALSE_VALUES = Set.of("false", "0", "no", "off");

    private final String dsn;
    private final String environment;
    private final String release;
    private final boolean sendDefaultPii;

    private SentryConfig(String dsn, String environment, String release, boolean sendDefaultPii) {
        this.dsn = dsn;
        this.environment = environment;
        this.release = release;
        this.sendDefaultPii = sendDefaultPii;
    }

    /**
     * @param env           looks up an environment variable, null if unset
     * @param pluginVersion the version of the running plugin, the default release name; null if unknown
     * @return the settings; a blank variable counts as unset
     */
    public static @NotNull SentryConfig fromEnvironment(@NotNull Function<String, String> env, @Nullable String pluginVersion) {
        String dsn = value(env, ENV_DSN);
        String environment = value(env, ENV_ENVIRONMENT);
        String release = value(env, ENV_RELEASE);
        String pii = value(env, ENV_SEND_DEFAULT_PII);
        return new SentryConfig(
                dsn,
                environment == null ? DEFAULT_ENVIRONMENT : environment,
                release != null ? release : blankToNull(pluginVersion),
                pii == null || !FALSE_VALUES.contains(pii.toLowerCase(Locale.ROOT)));
    }

    /** @return whether a DSN is set, so errors are reported at all */
    public boolean isEnabled() {
        return dsn != null;
    }

    /** @return the DSN, null when reporting is off; never log it */
    public @Nullable String getDsn() {
        return dsn;
    }

    public @NotNull String getEnvironment() {
        return environment;
    }

    /** @return the release name, null if neither the variable nor the plugin version gives one */
    public @Nullable String getRelease() {
        return release;
    }

    public boolean isSendDefaultPii() {
        return sendDefaultPii;
    }

    private static String value(Function<String, String> env, String key) {
        return blankToNull(env.apply(key));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
