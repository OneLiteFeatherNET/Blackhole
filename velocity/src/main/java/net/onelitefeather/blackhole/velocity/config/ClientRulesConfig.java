package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/**
 * The client rules ({@code clientRules} in {@code config.json}). {@code enabled} (default true,
 * {@value #ENV_ENABLED} overrides it) switches collection and enforcement off on this proxy;
 * {@code settle} is how long after the first server connect the traits are submitted for the
 * fingerprint (ISO-8601, default {@code PT5S}); {@code catalogue.minPlayers} is how many distinct players must
 * show a brand or channel before it is reported to the rule editor (default 3) and
 * {@code catalogue.heartbeat} the keep-alive period of the catalogue report (default {@code PT15M}).
 */
public final class ClientRulesConfig {

    public static final String ENV_ENABLED = "BLACKHOLE_CLIENT_RULES";

    static final Duration DEFAULT_SETTLE = Duration.ofSeconds(5);
    static final Duration DEFAULT_HEARTBEAT = Duration.ofMinutes(15);
    static final int DEFAULT_MIN_PLAYERS = 3;
    private static final Duration MINIMUM = Duration.ofSeconds(1);

    /** The catalogue part of the settings. */
    public static final class Catalogue {

        private Integer minPlayers;
        private String heartbeat;

        /** @return at least 1; the default 3 for a missing or smaller value */
        public int getMinPlayers() {
            return minPlayers == null || minPlayers < 1 ? DEFAULT_MIN_PLAYERS : minPlayers;
        }

        public @NotNull Duration getHeartbeat() {
            return parse(heartbeat, DEFAULT_HEARTBEAT);
        }
    }

    private Boolean enabled;
    private String settle;
    private Catalogue catalogue;

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public @NotNull Duration getSettle() {
        return parse(settle, DEFAULT_SETTLE);
    }

    public @NotNull Catalogue getCatalogue() {
        return catalogue == null ? new Catalogue() : catalogue;
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
     * @return the settings with the environment variable {@value #ENV_ENABLED} ({@code true} or {@code false}) applied if it is set
     */
    public @NotNull ClientRulesConfig withEnvironment(@NotNull Function<String, String> env) {
        String override = env.apply(ENV_ENABLED);
        ClientRulesConfig result = new ClientRulesConfig();
        result.settle = settle;
        result.catalogue = catalogue;
        result.enabled = override == null || override.isBlank() ? enabled : Boolean.parseBoolean(override.strip());
        return result;
    }
}
