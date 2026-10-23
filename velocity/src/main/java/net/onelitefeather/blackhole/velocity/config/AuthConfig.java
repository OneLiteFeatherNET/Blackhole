package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The credentials the proxy uses to get an access token for the backend (OAuth2 client credentials).
 * Every value can be overridden by an environment variable so the secret need not live in
 * {@code config.json}; see {@link #withEnvironment(Function)}.
 */
public final class AuthConfig {

    public static final String ENV_TENANT_ID = "BLACKHOLE_TENANT_ID";
    public static final String ENV_CLIENT_ID = "BLACKHOLE_CLIENT_ID";
    public static final String ENV_CLIENT_SECRET = "BLACKHOLE_CLIENT_SECRET";
    public static final String ENV_SCOPE = "BLACKHOLE_AUTH_SCOPE";
    public static final String ENV_TOKEN_URL = "BLACKHOLE_TOKEN_URL";

    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9.-]+");

    /** The Entra tenant (a GUID or a domain); not needed when {@link #tokenUrl} is set. */
    private String tenantId = "";

    /** The client id of the app registration of this proxy. */
    private String clientId = "";

    /** A secret of that app registration. */
    private String clientSecret = "";

    /** What the token is for, e.g. {@code api://<client id of the API>/.default}. */
    private String scope = "";

    /** Development only: the token endpoint to use instead of Entra's, e.g. of a mock server. */
    private String tokenUrl;

    /** Creates a configuration with no credentials. */
    public AuthConfig() {
    }

    private AuthConfig(String tenantId, String clientId, String clientSecret, String scope, String tokenUrl) {
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.tokenUrl = tokenUrl;
    }

    public @NotNull String getTenantId() {
        return clean(tenantId);
    }

    public @NotNull String getClientId() {
        return clean(clientId);
    }

    public @NotNull String getClientSecret() {
        return clean(clientSecret);
    }

    public @NotNull String getScope() {
        return clean(scope);
    }

    public @Nullable String getTokenUrl() {
        String url = clean(tokenUrl);
        return url.isEmpty() ? null : url;
    }

    /**
     * @param env looks up an environment variable, null if unset
     * @return a copy in which every environment variable that is set and not blank replaces the file value
     */
    public @NotNull AuthConfig withEnvironment(@NotNull Function<String, String> env) {
        return new AuthConfig(
                override(env, ENV_TENANT_ID, getTenantId()),
                override(env, ENV_CLIENT_ID, getClientId()),
                override(env, ENV_CLIENT_SECRET, getClientSecret()),
                override(env, ENV_SCOPE, getScope()),
                override(env, ENV_TOKEN_URL, getTokenUrl()));
    }

    /**
     * @return the settings still missing, named after the config key and the environment variable that sets it
     */
    public @NotNull List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (getTenantId().isEmpty() && getTokenUrl() == null) {
            missing.add("tenantId (" + ENV_TENANT_ID + ")");
        }
        if (getClientId().isEmpty()) {
            missing.add("clientId (" + ENV_CLIENT_ID + ")");
        }
        if (getClientSecret().isEmpty()) {
            missing.add("clientSecret (" + ENV_CLIENT_SECRET + ")");
        }
        if (getScope().isEmpty()) {
            missing.add("scope (" + ENV_SCOPE + ")");
        }
        return missing;
    }

    /**
     * @return whether a token can be requested with these settings
     */
    public boolean isComplete() {
        return missing().isEmpty();
    }

    /**
     * @return the token endpoint: the dev override if set, otherwise Entra's for the tenant
     * @throws IllegalArgumentException if the override is not an http(s) URL or the tenant is not a plain id or domain
     */
    public @NotNull URI tokenUri() {
        String override = getTokenUrl();
        if (override != null) {
            URI uri;
            try {
                uri = URI.create(override);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid auth.tokenUrl '" + override + "': " + e.getMessage(), e);
            }
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Invalid auth.tokenUrl '" + override + "': expected an http(s) URL");
            }
            return uri;
        }
        if (!TENANT.matcher(getTenantId()).matches()) {
            throw new IllegalArgumentException("Invalid auth.tenantId: expected a tenant GUID or domain");
        }
        return URI.create("https://login.microsoftonline.com/" + getTenantId() + "/oauth2/v2.0/token");
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "AuthConfig{tenantId='" + getTenantId() + "', clientId='" + getClientId()
                + "', clientSecret=" + (getClientSecret().isEmpty() ? "<unset>" : "<redacted>")
                + ", scope='" + getScope() + "', tokenUrl=" + getTokenUrl() + "}";
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String override(Function<String, String> env, String name, String fileValue) {
        String value = env.apply(name);
        return value == null || value.isBlank() ? fileValue : value.strip();
    }
}
