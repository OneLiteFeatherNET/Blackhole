package net.onelitefeather.blackhole.velocity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;

public class BlackholeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String baseUrl;

    /**
     * Redis connection used to read active-punishment state mirrored by the backend's
     * RedisSyncConsumer, so a login/chat check on this proxy can hit a shared, fast cache
     * instead of an HTTP call to the backend - and so a punishment applied on another proxy in
     * the network is enforced here immediately via pub/sub, not just on this player's next login.
     */
    private String redisUri;

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
     * Creates a new configuration with default values.
     */
    public BlackholeConfig() {
        this.baseUrl = "http://localhost:8080";
        this.redisUri = "redis://localhost:6379";
        this.fallbackServer = "lobby";
        this.appealUrl = "";
        this.appealText = "";
        this.timeZone = ZoneId.systemDefault().getId();
        this.evidence = new EvidenceConfig();
        this.auth = new AuthConfig();
        this.cloudnet = new CloudNetConfig();
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
     * Gets the Redis connection URI used for cross-proxy punishment-state sync.
     *
     * @return the Redis URI
     */
    public @NotNull String getRedisUri() {
        return redisUri;
    }

    /**
     * Sets the Redis connection URI used for cross-proxy punishment-state sync.
     *
     * @param redisUri the Redis URI
     */
    public void setRedisUri(@NotNull String redisUri) {
        this.redisUri = redisUri;
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
        try {
            if (Files.notExists(path)) {
                BlackholeConfig config = new BlackholeConfig();
                save(config, path);
                return config;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                return GSON.fromJson(reader, BlackholeConfig.class);
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
