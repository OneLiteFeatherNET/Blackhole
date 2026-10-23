package net.onelitefeather.blackhole.discord.config;

import net.onelitefeather.blackhole.discord.auth.TokenSettings;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Everything the bot is configured with, read from the environment and validated at start so a
 * wrong deployment fails immediately with every problem named, not on the first command.
 *
 * <p>Environment variables: {@code DISCORD_TOKEN}, {@code DISCORD_GUILD_ID},
 * {@code DISCORD_TEAM_CHANNEL_ID}, {@code DISCORD_STAFF_ROLE_ID}, {@code BLACKHOLE_BACKEND_URL},
 * {@code BLACKHOLE_PANEL_URL}, {@code BLACKHOLE_DISCORD_TENANT_ID}, {@code BLACKHOLE_DISCORD_CLIENT_ID},
 * {@code BLACKHOLE_DISCORD_CLIENT_SECRET}, {@code BLACKHOLE_DISCORD_AUTH_SCOPE}; optional
 * {@code BLACKHOLE_DISCORD_TOKEN_URL} (development: a mock token endpoint) and
 * {@code DISCORD_HEALTH_PORT} (default 8082, 0 turns the health endpoint off).</p>
 *
 * @param discordToken   the bot token (a secret, never printed)
 * @param guildId        the one guild the bot serves
 * @param teamChannelId  the channel that receives the team notifications
 * @param staffRoleId    the role that is pinged by the team notifications
 * @param backendUrl     the backend base URL without a trailing slash
 * @param panelUrl       the admin panel base URL without a trailing slash, for links into the panel
 * @param auth           the backend client credentials
 * @param healthPort     the port of the local health endpoint, 0 for none
 */
public record BotConfig(
        @NotNull String discordToken,
        long guildId,
        long teamChannelId,
        long staffRoleId,
        @NotNull String backendUrl,
        @NotNull String panelUrl,
        @NotNull TokenSettings auth,
        int healthPort
) {

    public static final String ENV_TOKEN = "DISCORD_TOKEN";
    public static final String ENV_GUILD_ID = "DISCORD_GUILD_ID";
    public static final String ENV_TEAM_CHANNEL_ID = "DISCORD_TEAM_CHANNEL_ID";
    public static final String ENV_STAFF_ROLE_ID = "DISCORD_STAFF_ROLE_ID";
    public static final String ENV_BACKEND_URL = "BLACKHOLE_BACKEND_URL";
    public static final String ENV_PANEL_URL = "BLACKHOLE_PANEL_URL";
    public static final String ENV_TENANT_ID = "BLACKHOLE_DISCORD_TENANT_ID";
    public static final String ENV_CLIENT_ID = "BLACKHOLE_DISCORD_CLIENT_ID";
    public static final String ENV_CLIENT_SECRET = "BLACKHOLE_DISCORD_CLIENT_SECRET";
    public static final String ENV_AUTH_SCOPE = "BLACKHOLE_DISCORD_AUTH_SCOPE";
    public static final String ENV_TOKEN_URL = "BLACKHOLE_DISCORD_TOKEN_URL";
    public static final String ENV_HEALTH_PORT = "DISCORD_HEALTH_PORT";

    /** The configuration is wrong; {@link #problems()} names every problem. */
    public static final class ConfigException extends Exception {

        private final List<String> problems;

        public ConfigException(@NotNull List<String> problems) {
            super("Invalid configuration: " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public @NotNull List<String> problems() {
            return this.problems;
        }
    }

    /**
     * @param env looks up an environment variable, null if unset
     * @return the configuration
     * @throws ConfigException with every missing or malformed value
     */
    public static @NotNull BotConfig fromEnvironment(@NotNull Function<String, String> env) throws ConfigException {
        List<String> problems = new ArrayList<>();
        String token = required(env, ENV_TOKEN, problems);
        long guildId = snowflake(env, ENV_GUILD_ID, problems);
        long teamChannelId = snowflake(env, ENV_TEAM_CHANNEL_ID, problems);
        long staffRoleId = snowflake(env, ENV_STAFF_ROLE_ID, problems);
        String backendUrl = url(env, ENV_BACKEND_URL, problems);
        String panelUrl = url(env, ENV_PANEL_URL, problems);
        TokenSettings auth = new TokenSettings(
                value(env, ENV_TENANT_ID), value(env, ENV_CLIENT_ID), value(env, ENV_CLIENT_SECRET),
                value(env, ENV_AUTH_SCOPE), value(env, ENV_TOKEN_URL));
        problems.addAll(auth.missing());
        if (auth.isComplete()) {
            try {
                auth.tokenUri();
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        int healthPort = 8082;
        String port = value(env, ENV_HEALTH_PORT);
        if (!port.isEmpty()) {
            try {
                healthPort = Integer.parseInt(port);
                if (healthPort < 0 || healthPort > 65535) {
                    problems.add(ENV_HEALTH_PORT + " must be between 0 and 65535");
                }
            } catch (NumberFormatException e) {
                problems.add(ENV_HEALTH_PORT + " must be a number");
            }
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new BotConfig(token, guildId, teamChannelId, staffRoleId, backendUrl, panelUrl, auth, healthPort);
    }

    /** Never prints the bot token or the client secret. */
    @Override
    public String toString() {
        return "BotConfig{discordToken=<redacted>, guildId=" + this.guildId + ", teamChannelId=" + this.teamChannelId
                + ", staffRoleId=" + this.staffRoleId + ", backendUrl=" + this.backendUrl + ", panelUrl=" + this.panelUrl
                + ", auth=" + this.auth + ", healthPort=" + this.healthPort + "}";
    }

    private static String value(Function<String, String> env, String name) {
        String value = env.apply(name);
        return value == null ? "" : value.strip();
    }

    private static String required(Function<String, String> env, String name, List<String> problems) {
        String value = value(env, name);
        if (value.isEmpty()) {
            problems.add(name + " is missing");
        }
        return value;
    }

    private static long snowflake(Function<String, String> env, String name, List<String> problems) {
        String value = value(env, name);
        if (value.isEmpty()) {
            problems.add(name + " is missing");
            return 0;
        }
        if (!value.matches("\\d{15,20}")) {
            problems.add(name + " must be a Discord id (15 to 20 digits)");
            return 0;
        }
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            problems.add(name + " must be a Discord id (15 to 20 digits)");
            return 0;
        }
    }

    private static String url(Function<String, String> env, String name, List<String> problems) {
        String value = value(env, name);
        if (value.isEmpty()) {
            problems.add(name + " is missing");
            return "";
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                problems.add(name + " must be an absolute http(s) URL without query or fragment");
                return "";
            }
        } catch (IllegalArgumentException e) {
            problems.add(name + " is not a valid URL");
            return "";
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
