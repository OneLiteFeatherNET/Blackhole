package net.onelitefeather.blackhole.discord.config;

import net.onelitefeather.blackhole.discord.auth.TokenSettings;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
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
 * {@code BLACKHOLE_DISCORD_CLIENT_SECRET}, {@code BLACKHOLE_DISCORD_AUTH_SCOPE}, {@code RABBITMQ_HOST},
 * {@code RABBITMQ_USER}, {@code RABBITMQ_PASSWORD}; optional {@code RABBITMQ_PORT} (5672), {@code RABBITMQ_VHOST} (/),
 * {@code BLACKHOLE_DISCORD_TOKEN_URL} (development: a mock token endpoint) and
 * {@code DISCORD_HEALTH_PORT} (default 8082, 0 turns the health endpoint off).</p>
 *
 * <p>Tickets: {@code DISCORD_TICKET_CATEGORY_ID} and {@code DISCORD_ARCHIVE_CATEGORY_ID} (two different categories);
 * optional {@code DISCORD_APPEAL_CHANNEL_ID} (the channel that holds the "open an appeal" button),
 * {@code DISCORD_ARCHIVE_DELETE_AFTER} (ISO-8601, default {@code P30D}, {@code PT0S} keeps archived channels),
 * {@code DISCORD_SYNC_INTERVAL} (default {@code PT30S}) and {@code DISCORD_STAFF_RECONCILE_INTERVAL} (default {@code PT5M}).</p>
 *
 * @param discordToken   the bot token (a secret, never printed)
 * @param guildId        the one guild the bot serves
 * @param teamChannelId  the channel that receives the team notifications
 * @param staffRoleId    the role that is pinged by the team notifications
 * @param backendUrl     the backend base URL without a trailing slash
 * @param panelUrl       the admin panel base URL without a trailing slash, for links into the panel
 * @param auth           the backend client credentials
 * @param rabbit         the broker the report and appeal events come from
 * @param healthPort     the port of the local health endpoint, 0 for none
 * @param tickets        the appeal ticket channels
 */
public record BotConfig(
        @NotNull String discordToken,
        long guildId,
        long teamChannelId,
        long staffRoleId,
        @NotNull String backendUrl,
        @NotNull String panelUrl,
        @NotNull TokenSettings auth,
        @NotNull RabbitSettings rabbit,
        int healthPort,
        @NotNull TicketSettings tickets
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
    public static final String ENV_RABBIT_HOST = "RABBITMQ_HOST";
    public static final String ENV_RABBIT_PORT = "RABBITMQ_PORT";
    public static final String ENV_RABBIT_USER = "RABBITMQ_USER";
    public static final String ENV_RABBIT_PASSWORD = "RABBITMQ_PASSWORD";
    public static final String ENV_RABBIT_VHOST = "RABBITMQ_VHOST";
    public static final String ENV_HEALTH_PORT = "DISCORD_HEALTH_PORT";
    public static final String ENV_TICKET_CATEGORY_ID = "DISCORD_TICKET_CATEGORY_ID";
    public static final String ENV_ARCHIVE_CATEGORY_ID = "DISCORD_ARCHIVE_CATEGORY_ID";
    public static final String ENV_APPEAL_CHANNEL_ID = "DISCORD_APPEAL_CHANNEL_ID";
    public static final String ENV_ARCHIVE_DELETE_AFTER = "DISCORD_ARCHIVE_DELETE_AFTER";
    public static final String ENV_SYNC_INTERVAL = "DISCORD_SYNC_INTERVAL";
    public static final String ENV_RECONCILE_INTERVAL = "DISCORD_STAFF_RECONCILE_INTERVAL";

    /**
     * @param ticketCategoryId  the category the ticket channels are created in
     * @param archiveCategoryId the category decided tickets move to
     * @param appealChannelId   the channel with the "open an appeal" button, 0 for none
     * @param deleteAfter       how long an archived channel lives; zero keeps it
     * @param syncInterval      how often the backend's cursors are polled
     * @param reconcileInterval how often channel access is reconciled with the staff links and the role
     */
    public record TicketSettings(long ticketCategoryId, long archiveCategoryId, long appealChannelId, @NotNull Duration deleteAfter,
                                 @NotNull Duration syncInterval, @NotNull Duration reconcileInterval) {
    }

    /**
     * @param host     the RabbitMQ host
     * @param port     its AMQP port
     * @param user     the user to connect as
     * @param password its password (a secret, never printed)
     * @param vhost    the virtual host
     */
    public record RabbitSettings(@NotNull String host, int port, @NotNull String user, @NotNull String password, @NotNull String vhost) {

        @Override
        public String toString() {
            return "RabbitSettings{host=" + this.host + ", port=" + this.port + ", user=" + this.user
                    + ", password=<redacted>, vhost=" + this.vhost + "}";
        }
    }

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
        String rabbitHost = required(env, ENV_RABBIT_HOST, problems);
        String rabbitUser = required(env, ENV_RABBIT_USER, problems);
        String rabbitPassword = required(env, ENV_RABBIT_PASSWORD, problems);
        int rabbitPort = 5672;
        String rabbitPortText = value(env, ENV_RABBIT_PORT);
        if (!rabbitPortText.isEmpty()) {
            try {
                rabbitPort = Integer.parseInt(rabbitPortText);
                if (rabbitPort < 1 || rabbitPort > 65535) {
                    problems.add(ENV_RABBIT_PORT + " must be between 1 and 65535");
                }
            } catch (NumberFormatException e) {
                problems.add(ENV_RABBIT_PORT + " must be a number");
            }
        }
        String vhost = value(env, ENV_RABBIT_VHOST);
        RabbitSettings rabbit = new RabbitSettings(rabbitHost, rabbitPort, rabbitUser, rabbitPassword, vhost.isEmpty() ? "/" : vhost);
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
        long ticketCategory = snowflake(env, ENV_TICKET_CATEGORY_ID, problems);
        long archiveCategory = snowflake(env, ENV_ARCHIVE_CATEGORY_ID, problems);
        if (ticketCategory != 0 && ticketCategory == archiveCategory) {
            problems.add(ENV_TICKET_CATEGORY_ID + " and " + ENV_ARCHIVE_CATEGORY_ID + " must be different categories");
        }
        long appealChannel = value(env, ENV_APPEAL_CHANNEL_ID).isEmpty() ? 0 : snowflake(env, ENV_APPEAL_CHANNEL_ID, problems);
        Duration deleteAfter = duration(env, ENV_ARCHIVE_DELETE_AFTER, Duration.ofDays(30), Duration.ZERO, problems);
        Duration syncInterval = duration(env, ENV_SYNC_INTERVAL, Duration.ofSeconds(30), Duration.ofSeconds(5), problems);
        Duration reconcileInterval = duration(env, ENV_RECONCILE_INTERVAL, Duration.ofMinutes(5), Duration.ofSeconds(30), problems);
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new BotConfig(token, guildId, teamChannelId, staffRoleId, backendUrl, panelUrl, auth, rabbit, healthPort,
                new TicketSettings(ticketCategory, archiveCategory, appealChannel, deleteAfter, syncInterval, reconcileInterval));
    }

    /** Never prints the bot token or the client secret. */
    @Override
    public String toString() {
        return "BotConfig{discordToken=<redacted>, guildId=" + this.guildId + ", teamChannelId=" + this.teamChannelId
                + ", staffRoleId=" + this.staffRoleId + ", backendUrl=" + this.backendUrl + ", panelUrl=" + this.panelUrl
                + ", auth=" + this.auth + ", rabbit=" + this.rabbit + ", healthPort=" + this.healthPort + ", tickets=" + this.tickets + "}";
    }

    private static Duration duration(Function<String, String> env, String name, Duration fallback, Duration minimum, List<String> problems) {
        String value = value(env, name);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            Duration parsed = Duration.parse(value);
            if (parsed.compareTo(minimum) < 0) {
                problems.add(name + " must be at least " + minimum);
                return fallback;
            }
            return parsed;
        } catch (DateTimeParseException e) {
            problems.add(name + " must be an ISO-8601 duration such as PT30S");
            return fallback;
        }
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
