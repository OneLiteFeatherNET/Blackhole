package net.onelitefeather.blackhole.velocity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class BlackholeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String baseUrl;

    /**
     * The tenant (network/organization) this proxy belongs to. Every backend call is scoped
     * to this tenant.
     */
    private String tenantId;

    /**
     * A SERVICE-role JWT scoped to {@link #tenantId}, minted via {@code POST /auth/token} by a
     * PLATFORM_ADMIN or TENANT_ADMIN (see the backend's AuthController). Sent as a Bearer token
     * on every request; there is no login flow, this token is provisioned out-of-band and pasted
     * into this config file.
     */
    private String serviceToken;

    /**
     * Creates a new configuration with default values.
     */
    public BlackholeConfig() {
        this.baseUrl = "http://localhost:8080";
        this.tenantId = "00000000-0000-0000-0000-000000000000";
        this.serviceToken = "";
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
     * Gets the tenant this proxy belongs to.
     *
     * @return the tenant identifier
     */
    public @NotNull UUID getTenantId() {
        return UUID.fromString(tenantId);
    }

    /**
     * Sets the tenant this proxy belongs to.
     *
     * @param tenantId the tenant identifier
     */
    public void setTenantId(@NotNull UUID tenantId) {
        this.tenantId = tenantId.toString();
    }

    /**
     * Gets the SERVICE token sent as a Bearer token on every backend request.
     *
     * @return the service token, or an empty string if none has been configured yet
     */
    public @NotNull String getServiceToken() {
        return serviceToken;
    }

    /**
     * Sets the SERVICE token sent as a Bearer token on every backend request.
     *
     * @param serviceToken the service token
     */
    public void setServiceToken(@NotNull String serviceToken) {
        this.serviceToken = serviceToken;
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
