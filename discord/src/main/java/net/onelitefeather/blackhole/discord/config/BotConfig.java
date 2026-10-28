package net.onelitefeather.blackhole.discord.config;

import net.onelitefeather.blackhole.discord.audit.WebhookUrl;
import net.onelitefeather.blackhole.discord.auth.TokenSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Everything the bot is configured with, read from the environment and validated at start so a
 * wrong deployment fails immediately with every problem named, not on the first command.
 *
 * <p>Environment variables: {@code DISCORD_TOKEN}, {@code DISCORD_GUILD_ID},
 * {@code DISCORD_TEAM_CHANNEL_ID}, {@code DISCORD_STAFF_ROLE_ID}, {@code BLACKHOLE_BACKEND_URL},
 * {@code BLACKHOLE_PANEL_URL}, {@code BLACKHOLE_DISCORD_TENANT_ID}, {@code BLACKHOLE_DISCORD_CLIENT_ID},
 * {@code BLACKHOLE_DISCORD_CLIENT_SECRET}, {@code BLACKHOLE_DISCORD_AUTH_SCOPE}, {@code NATS_URL}; optional
 * {@code NATS_CREDS_FILE} (path of the bot's {@code .creds} file; empty for a server without authentication),
 * {@code BLACKHOLE_DISCORD_TOKEN_URL} (development: a mock token endpoint) and
 * {@code DISCORD_HEALTH_PORT} (default 8082, 0 turns the health endpoint off).</p>
 *
 * <p>Tickets: {@code DISCORD_TICKET_CATEGORY_ID} and {@code DISCORD_ARCHIVE_CATEGORY_ID} (two different categories);
 * optional {@code DISCORD_APPEAL_CHANNEL_ID} (the channel that holds the "open an appeal" button),
 * {@code DISCORD_ARCHIVE_DELETE_AFTER} (ISO-8601, default {@code P30D}, {@code PT0S} keeps archived channels),
 * {@code DISCORD_SYNC_INTERVAL} (default {@code PT30S}) and {@code DISCORD_STAFF_RECONCILE_INTERVAL} (default {@code PT5M}).</p>
 *
 * <p>Leak scanning: {@code DISCORD_LEAK_SCAN} ({@code PUBLIC} by default, {@code LIST} or {@code OFF}) and, for {@code LIST},
 * {@code DISCORD_LEAK_SCAN_CHANNELS}, a comma separated list of channel or category ids.</p>
 *
 * <p>Staff audit: optional {@code DISCORD_AUDIT_WEBHOOK_URL} (a Discord webhook URL of the audit channel; unset turns the audit off)
 * and {@code DISCORD_AUDIT_MAX_DELIVER} (deliveries per entry before it is dropped, default 50).</p>
 *
 * @param discordToken   the bot token (a secret, never printed)
 * @param guildId        the one guild the bot serves
 * @param teamChannelId  the channel that receives the team notifications
 * @param staffRoleId    the role that is pinged by the team notifications
 * @param backendUrl     the backend base URL without a trailing slash
 * @param panelUrl       the admin panel base URL without a trailing slash, for links into the panel
 * @param auth           the backend client credentials
 * @param nats           the NATS server the report, appeal and case events come from
 * @param healthPort     the port of the local health endpoint, 0 for none
 * @param tickets        the appeal ticket channels
 * @param leak           which channels are scanned for posted Ban IDs
 * @param audit          where the staff action audit is posted (off when no webhook is set)
 */
public record BotConfig(
        @NotNull String discordToken,
        long guildId,
        long teamChannelId,
        long staffRoleId,
        @NotNull String backendUrl,
        @NotNull String panelUrl,
        @NotNull TokenSettings auth,
        @NotNull NatsSettings nats,
        int healthPort,
        @NotNull TicketSettings tickets,
        @NotNull LeakSettings leak,
        @NotNull AuditSettings audit
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
    public static final String ENV_NATS_URL = "NATS_URL";
    public static final String ENV_NATS_CREDS_FILE = "NATS_CREDS_FILE";
    public static final String ENV_HEALTH_PORT = "DISCORD_HEALTH_PORT";
    public static final String ENV_TICKET_CATEGORY_ID = "DISCORD_TICKET_CATEGORY_ID";
    public static final String ENV_ARCHIVE_CATEGORY_ID = "DISCORD_ARCHIVE_CATEGORY_ID";
    public static final String ENV_APPEAL_CHANNEL_ID = "DISCORD_APPEAL_CHANNEL_ID";
    public static final String ENV_ARCHIVE_DELETE_AFTER = "DISCORD_ARCHIVE_DELETE_AFTER";
    public static final String ENV_SYNC_INTERVAL = "DISCORD_SYNC_INTERVAL";
    public static final String ENV_RECONCILE_INTERVAL = "DISCORD_STAFF_RECONCILE_INTERVAL";
    public static final String ENV_LEAK_SCAN = "DISCORD_LEAK_SCAN";
    public static final String ENV_LEAK_SCAN_CHANNELS = "DISCORD_LEAK_SCAN_CHANNELS";
    public static final String ENV_AUDIT_WEBHOOK_URL = "DISCORD_AUDIT_WEBHOOK_URL";
    public static final String ENV_AUDIT_MAX_DELIVER = "DISCORD_AUDIT_MAX_DELIVER";
    static final int AUDIT_MAX_DELIVER_DEFAULT = 50;

    /** Which channels are scanned for Ban IDs. */
    public enum LeakMode {
        /** Every channel and thread where {@code @everyone} can view, except the ticket and archive categories. */
        PUBLIC,
        /** Only the configured channels (or categories). */
        LIST,
        /** No scanning; no message content is sent to the backend. */
        OFF
    }

    /**
     * @param mode       what is scanned
     * @param channelIds with {@link LeakMode#LIST}: the channels or categories to scan
     */
    public record LeakSettings(@NotNull LeakMode mode, @NotNull Set<Long> channelIds) {
    }

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
     * @param url       the server URL(s), comma separated: {@code nats://}, {@code tls://}, {@code ws://} or {@code wss://}; it may
     *                  carry a user and password, which are never printed
     * @param credsFile the path of the {@code .creds} file of the bot's NATS user, empty for none
     */
    public record NatsSettings(@NotNull String url, @NotNull String credsFile) {

        /** The URL(s) with any user and password replaced, for logs and the string form. */
        public static @NotNull String redact(@NotNull String url) {
            return url.replaceAll("(?i)((?:nats|tls|ws|wss)://)[^/@\\s,]*@", "$1<redacted>@");
        }

        @Override
        public String toString() {
            return "NatsSettings{url=" + redact(this.url) + ", credsFile=" + this.credsFile + "}";
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
        TokenSettings auth = TokenSettings.of(
                value(env, ENV_TENANT_ID), value(env, ENV_CLIENT_ID), value(env, ENV_CLIENT_SECRET),
                value(env, ENV_AUTH_SCOPE), value(env, ENV_TOKEN_URL), env);
        problems.addAll(auth.missing());
        if (auth.isComplete()) {
            try {
                auth.tokenUri();
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        NatsSettings nats = nats(env, problems);
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
        LeakSettings leak = leak(env, problems);
        AuditSettings audit = audit(env, problems);
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new BotConfig(token, guildId, teamChannelId, staffRoleId, backendUrl, panelUrl, auth, nats, healthPort,
                new TicketSettings(ticketCategory, archiveCategory, appealChannel, deleteAfter, syncInterval, reconcileInterval), leak, audit);
    }

    /** Never prints the bot token, the client secret or the audit webhook URL. */
    @Override
    public String toString() {
        return "BotConfig{discordToken=<redacted>, guildId=" + this.guildId + ", teamChannelId=" + this.teamChannelId
                + ", staffRoleId=" + this.staffRoleId + ", backendUrl=" + this.backendUrl + ", panelUrl=" + this.panelUrl
                + ", auth=" + this.auth + ", nats=" + this.nats + ", healthPort=" + this.healthPort + ", tickets=" + this.tickets
                + ", leak=" + this.leak + ", audit=" + this.audit + "}";
    }

    /**
     * @param webhookUrl the Discord webhook the staff audit posts to, null when the audit is off; a secret, never printed
     */
    public record AuditSettings(@Nullable String webhookUrl, int maxDeliver) {

        @Override
        public String toString() {
            return "AuditSettings{webhook=" + (this.webhookUrl == null ? "off" : "<redacted>") + ", maxDeliver=" + this.maxDeliver + "}";
        }
    }

    /**
     * Unset webhook means the audit is off; set means it must be a Discord webhook URL, or the bot does not start. The
     * delivery limit is how often a message the webhook keeps refusing is tried before it is dropped.
     */
    private static AuditSettings audit(Function<String, String> env, List<String> problems) {
        int maxDeliver = AUDIT_MAX_DELIVER_DEFAULT;
        String limit = value(env, ENV_AUDIT_MAX_DELIVER);
        if (!limit.isEmpty()) {
            try {
                maxDeliver = Integer.parseInt(limit);
                if (maxDeliver < 1) {
                    problems.add(ENV_AUDIT_MAX_DELIVER + " must be at least 1");
                }
            } catch (NumberFormatException e) {
                problems.add(ENV_AUDIT_MAX_DELIVER + " must be a whole number");
            }
        }
        String url = value(env, ENV_AUDIT_WEBHOOK_URL);
        if (url.isEmpty()) {
            return new AuditSettings(null, maxDeliver);
        }
        if (!WebhookUrl.isValid(url)) {
            // Names the variable only: the value is a secret and is not echoed back.
            problems.add(ENV_AUDIT_WEBHOOK_URL + " must be a Discord webhook URL (https://discord.com/api/webhooks/<id>/<token>)");
            return new AuditSettings(null, maxDeliver);
        }
        return new AuditSettings(url, maxDeliver);
    }

    private static NatsSettings nats(Function<String, String> env, List<String> problems) {
        String url = required(env, ENV_NATS_URL, problems);
        if (!url.isEmpty()) {
            for (String server : url.split("\\s*,\\s*")) {
                if (!server.matches("(?i)(nats|tls|ws|wss)://\\S+")) {
                    problems.add(ENV_NATS_URL + " must list nats://, tls://, ws:// or wss:// URLs, separated by commas");
                    break;
                }
            }
        }
        String creds = value(env, ENV_NATS_CREDS_FILE);
        if (!creds.isEmpty() && !Files.isReadable(Path.of(creds))) {
            problems.add(ENV_NATS_CREDS_FILE + " must be the path of a readable file");
        }
        return new NatsSettings(url, creds);
    }

    private static LeakSettings leak(Function<String, String> env, List<String> problems) {
        String modeText = value(env, ENV_LEAK_SCAN);
        LeakMode mode = LeakMode.PUBLIC;
        if (!modeText.isEmpty()) {
            try {
                mode = LeakMode.valueOf(modeText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add(ENV_LEAK_SCAN + " must be PUBLIC, LIST or OFF");
            }
        }
        Set<Long> channels = new LinkedHashSet<>();
        String list = value(env, ENV_LEAK_SCAN_CHANNELS);
        if (mode == LeakMode.LIST) {
            if (list.isEmpty()) {
                problems.add(ENV_LEAK_SCAN_CHANNELS + " is missing (" + ENV_LEAK_SCAN + "=LIST scans only the listed channels)");
            }
            for (String id : list.split(",")) {
                String trimmed = id.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.matches("\\d{15,20}")) {
                    problems.add(ENV_LEAK_SCAN_CHANNELS + " must be a comma separated list of Discord ids (15 to 20 digits)");
                    break;
                }
                try {
                    channels.add(Long.parseUnsignedLong(trimmed));
                } catch (NumberFormatException e) {
                    problems.add(ENV_LEAK_SCAN_CHANNELS + " must be a comma separated list of Discord ids (15 to 20 digits)");
                    break;
                }
            }
        }
        return new LeakSettings(mode, Set.copyOf(channels));
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
