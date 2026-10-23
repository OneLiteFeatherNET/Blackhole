package net.onelitefeather.blackhole.discord.auth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The credentials the bot uses to get an access token for the backend (OAuth2 client credentials,
 * the Entra app role {@code Blackhole.Discord}). Same shape as the Velocity plugin's {@code AuthConfig}.
 * Never prints the secret.
 *
 * @param tenantId     the Entra tenant (a GUID or a domain); not needed when {@code tokenUrl} is set
 * @param clientId     the client id of the bot's app registration
 * @param clientSecret a secret of that app registration
 * @param scope        what the token is for, e.g. {@code api://<client id of the API>/.default}
 * @param tokenUrl     development only: the token endpoint to use instead of Entra's, or null
 */
public record TokenSettings(
        @NotNull String tenantId,
        @NotNull String clientId,
        @NotNull String clientSecret,
        @NotNull String scope,
        @Nullable String tokenUrl
) {

    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9.-]+");

    public TokenSettings {
        tenantId = clean(tenantId);
        clientId = clean(clientId);
        clientSecret = clean(clientSecret);
        scope = clean(scope);
        tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? null : tokenUrl.strip();
    }

    public @NotNull String getClientId() {
        return this.clientId;
    }

    public @NotNull String getClientSecret() {
        return this.clientSecret;
    }

    public @NotNull String getScope() {
        return this.scope;
    }

    /** @return the settings still missing, named after the environment variable that sets them */
    public @NotNull List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (this.tenantId.isEmpty() && this.tokenUrl == null) {
            missing.add("tenantId (BLACKHOLE_DISCORD_TENANT_ID)");
        }
        if (this.clientId.isEmpty()) {
            missing.add("clientId (BLACKHOLE_DISCORD_CLIENT_ID)");
        }
        if (this.clientSecret.isEmpty()) {
            missing.add("clientSecret (BLACKHOLE_DISCORD_CLIENT_SECRET)");
        }
        if (this.scope.isEmpty()) {
            missing.add("scope (BLACKHOLE_DISCORD_AUTH_SCOPE)");
        }
        return missing;
    }

    /** @return whether a token can be requested with these settings */
    public boolean isComplete() {
        return missing().isEmpty();
    }

    /**
     * @return the token endpoint: the dev override if set, otherwise Entra's for the tenant
     * @throws IllegalArgumentException if the override is not an http(s) URL or the tenant is not a plain id or domain
     */
    public @NotNull URI tokenUri() {
        if (this.tokenUrl != null) {
            URI uri;
            try {
                uri = URI.create(this.tokenUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid token URL '" + this.tokenUrl + "': " + e.getMessage(), e);
            }
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Invalid token URL '" + this.tokenUrl + "': expected an http(s) URL");
            }
            return uri;
        }
        if (!TENANT.matcher(this.tenantId).matches()) {
            throw new IllegalArgumentException("Invalid BLACKHOLE_DISCORD_TENANT_ID: expected a tenant GUID or domain");
        }
        return URI.create("https://login.microsoftonline.com/" + this.tenantId + "/oauth2/v2.0/token");
    }

    @Override
    public String toString() {
        return "TokenSettings{tenantId='" + this.tenantId + "', clientId='" + this.clientId
                + "', clientSecret=" + (this.clientSecret.isEmpty() ? "<unset>" : "<redacted>")
                + ", scope='" + this.scope + "', tokenUrl=" + this.tokenUrl + "}";
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
