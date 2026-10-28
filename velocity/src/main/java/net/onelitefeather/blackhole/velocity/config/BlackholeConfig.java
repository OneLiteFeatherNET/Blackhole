package net.onelitefeather.blackhole.velocity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class BlackholeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlackholeConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** The setting of the Redis era; read only to warn that it is ignored. */
    private static final String LEGACY_REDIS_URI = "redisUri";

    private String baseUrl;

    /**
     * The NATS connection the proxy receives the backend's events over. Absent in configs written before
     * this setting existed, which {@link #getNats()} treats as the defaults.
     */
    private NatsConfig nats;

    /**
     * The backend server a player is sent to when a SERVER-section punishment arrives while they
     * stand on a server it covers. If this server does not exist, is itself covered by the
     * punishment or cannot be reached, the player is disconnected instead.
     */
    private String fallbackServer;

    /**
     * Where punished players can contest a punishment, shown on their punishment screen. Empty to
     * show no appeal hint. Only http(s) links are used.
     */
    private String appealUrl;

    /**
     * Optional label for {@link #appealUrl} (e.g. "our forum"); the link itself is shown if empty.
     */
    private String appealText;

    /**
     * The address of the admin panel, for the "panel" button of the new-report notice to staff (the report
     * page is {@code <panelUrl>/reports/<id>}). Empty to show no button. Only http(s) links are used.
     */
    private String panelUrl;

    /**
     * The time zone absolute punishment end times are shown in (an id like {@code Europe/Berlin});
     * the proxy's system zone if empty or invalid.
     */
    private String timeZone;

    /**
     * Evidence the proxy can attach to punishments. Absent in configs written before this setting
     * existed, which {@link #getEvidence()} treats as everything off.
     */
    private EvidenceConfig evidence;

    /**
     * How the proxy authenticates to the backend. Absent in configs written before this setting
     * existed, which {@link #getAuth()} treats as no credentials.
     */
    private AuthConfig auth;

    /**
     * The optional CloudNet integration. Absent in configs written before this setting existed,
     * which {@link #getCloudnet()} treats as {@code auto}.
     */
    private CloudNetConfig cloudnet;

    /**
     * The client rules (collection, enforcement, fingerprint submission and the editor catalogue). Absent in configs
     * written before this setting existed, which {@link #getClientRules()} treats as the defaults.
     */
    private ClientRulesConfig clientRules;

    /**
     * The mute settings, including the commands a mute covers. Absent in configs written before this
     * setting existed, which {@link #getMute()} treats as the defaults.
     */
    private MuteConfig mute;

    /**
     * Creates a new configuration with default values.
     */
    public BlackholeConfig() {
        this.baseUrl = "http://localhost:8080";
        this.fallbackServer = "lobby";
        this.appealUrl = "";
        this.appealText = "";
        this.panelUrl = "";
        this.timeZone = ZoneId.systemDefault().getId();
        this.evidence = new EvidenceConfig();
        this.auth = new AuthConfig();
        this.cloudnet = new CloudNetConfig();
        this.clientRules = new ClientRulesConfig();
        this.nats = new NatsConfig();
    }

    /**
     * @return the mute settings; the default command list for configs written before they existed
     */
    public @NotNull MuteConfig getMute() {
        return mute == null ? new MuteConfig() : mute;
    }

    /**
     * @return the client rule settings; the defaults for configs written before they existed
     */
    public @NotNull ClientRulesConfig getClientRules() {
        return clientRules == null ? new ClientRulesConfig() : clientRules;
    }

    /**
     * @return the file's NATS settings, without the environment overrides (see
     * {@link NatsConfig#withEnvironment}); the defaults for configs written before they existed
     */
    public @NotNull NatsConfig getNats() {
        return nats == null ? new NatsConfig() : nats;
    }

    /**
     * @return the CloudNet integration settings; {@code auto} for configs written before they existed
     */
    public @NotNull CloudNetConfig getCloudnet() {
        return cloudnet == null ? new CloudNetConfig() : cloudnet;
    }

    /**
     * @return the file's backend credentials, without the environment overrides (see
     * {@link AuthConfig#withEnvironment}); empty for configs written before they existed
     */
    public @NotNull AuthConfig getAuth() {
        return auth == null ? new AuthConfig() : auth;
    }

    /**
     * @return the evidence settings; everything off for configs written before they existed
     */
    public @NotNull EvidenceConfig getEvidence() {
        return evidence == null ? new EvidenceConfig() : evidence;
    }

    /**
     * @return the appeal link, or null if none (or not an http/https link) is configured
     */
    public @Nullable String getAppealUrl() {
        if (appealUrl == null) {
            return null;
        }
        String url = appealUrl.strip();
        return url.startsWith("https://") || url.startsWith("http://") ? url : null;
    }

    /**
     * @return the label shown for the appeal link, or null if none is configured
     */
    public @Nullable String getAppealText() {
        return appealText == null || appealText.isBlank() ? null : appealText.strip();
    }

    /**
     * @return the admin panel's address without a trailing slash, or null if none (or not an http/https
     * link) is configured
     */
    public @Nullable String getPanelUrl() {
        if (panelUrl == null) {
            return null;
        }
        String url = panelUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.startsWith("https://") || url.startsWith("http://") ? url : null;
    }

    public void setPanelUrl(@NotNull String panelUrl) {
        this.panelUrl = panelUrl;
    }

    public void setAppealUrl(@NotNull String appealUrl) {
        this.appealUrl = appealUrl;
    }

    public void setAppealText(@NotNull String appealText) {
        this.appealText = appealText;
    }

    public void setTimeZone(@NotNull String timeZone) {
        this.timeZone = timeZone;
    }

    /**
     * @return the configured time zone, the system zone if unset or invalid
     */
    public @NotNull ZoneId getZone() {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timeZone.strip());
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /**
     * Gets the base URL for the Blackhole API.
     *
     * @return the base URL
     */
    public @NotNull String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Sets the base URL for the Blackhole API.
     *
     * @param baseUrl the base URL
     */
    public void setBaseUrl(@NotNull String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Gets the name of the server players are moved to when a server ban arrives while they play on
     * a covered server.
     *
     * @return the fallback server name; {@code lobby} for configs written before this setting existed
     */
    public @NotNull String getFallbackServer() {
        return fallbackServer == null || fallbackServer.isBlank() ? "lobby" : fallbackServer;
    }

    /**
     * Sets the fallback server.
     *
     * @param fallbackServer the server name
     */
    public void setFallbackServer(@NotNull String fallbackServer) {
        this.fallbackServer = fallbackServer;
    }

    /**
     * Loads the configuration from the specified file.
     *
     * @param path the path to the configuration file
     * @return the loaded configuration
     */
    public static @NotNull BlackholeConfig load(@NotNull Path path) {
        return load(path, LOGGER::warn);
    }

    /**
     * Loads the configuration from the specified file.
     *
     * @param path the path to the configuration file
     * @param warn receives the warnings about the file's content (a legacy setting that is now ignored)
     * @return the loaded configuration
     */
    public static @NotNull BlackholeConfig load(@NotNull Path path, @NotNull Consumer<String> warn) {
        try {
            if (Files.notExists(path)) {
                BlackholeConfig config = new BlackholeConfig();
                save(config, path);
                return config;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject raw = GSON.fromJson(reader, JsonObject.class);
                if (raw != null && raw.has(LEGACY_REDIS_URI)) {
                    warn.accept("config.json still contains " + LEGACY_REDIS_URI + ": the plugin no longer uses Redis and ignores it. "
                            + "Set the nats section (url, credsFile) and remove the old key.");
                }
                BlackholeConfig config = GSON.fromJson(raw, BlackholeConfig.class);
                return config == null ? new BlackholeConfig() : config;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    /**
     * Saves the configuration to the specified file.
     *
     * @param config the configuration to save
     * @param path   the path to the configuration file
     */
    public static void save(@NotNull BlackholeConfig config, @NotNull Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save configuration", e);
        }
    }
}
