package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.function.Function;

/**
 * The optional CloudNet integration ({@code cloudnet} in {@code config.json}). With {@code auto}
 * (the default) the plugin uses CloudNet when it runs as a CloudNet service; {@code off} never
 * probes for it. Any other value counts as {@code auto}.
 */
public final class CloudNetConfig {

    public static final String ENV_INTEGRATION = "BLACKHOLE_CLOUDNET_INTEGRATION";

    private String integration;

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
     * @return the setting with the environment variable {@value #ENV_INTEGRATION} applied if it is set
     */
    public @NotNull CloudNetConfig withEnvironment(@NotNull Function<String, String> env) {
        String override = env.apply(ENV_INTEGRATION);
        CloudNetConfig result = new CloudNetConfig();
        result.integration = override == null || override.isBlank() ? getIntegration() : override.strip().toLowerCase(Locale.ROOT);
        return result;
    }
}
