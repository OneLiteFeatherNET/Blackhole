package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthConfigTest {

    private static final String FILE = """
            {
              "baseUrl": "http://localhost:8080",
              "auth": {
                "tenantId": "file-tenant",
                "clientId": "file-client",
                "clientSecret": "file-secret",
                "scope": "api://api-id/.default"
              }
            }
            """;

    private static AuthConfig load(Path dir, String json) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return BlackholeConfig.load(file).getAuth();
    }

    @Test
    void aNewConfigHasNoCredentialsAndIsIncomplete() {
        AuthConfig auth = new BlackholeConfig().getAuth();

        assertFalse(auth.isComplete(), "no credentials is not complete");
        assertEquals(4, auth.missing().size(), "tenant, client id, secret and scope are missing: " + auth.missing());
    }

    @Test
    void theFirstStartWritesAnAuthSectionWithEmptyCredentials(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");

        BlackholeConfig.load(file);

        String written = Files.readString(file);
        assertTrue(written.contains("\"auth\""), "the file has an auth section: " + written);
        assertTrue(written.contains("\"clientSecret\": \"\""), "with an empty secret: " + written);
        assertFalse(written.contains("tokenUrl"), "the dev-only override is not advertised: " + written);
    }

    @Test
    void valuesComeFromTheFile(@TempDir Path dir) throws IOException {
        AuthConfig auth = load(dir, FILE);

        assertEquals("file-tenant", auth.getTenantId(), "tenant");
        assertEquals("file-client", auth.getClientId(), "client id");
        assertEquals("file-secret", auth.getClientSecret(), "secret");
        assertEquals("api://api-id/.default", auth.getScope(), "scope");
        assertNull(auth.getTokenUrl(), "no override");
        assertTrue(auth.isComplete(), "complete");
    }

    @Test
    void aConfigWithoutAnAuthSectionHasNoCredentials(@TempDir Path dir) throws IOException {
        AuthConfig auth = load(dir, "{\"baseUrl\":\"http://localhost:8080\"}");

        assertFalse(auth.isComplete(), "configs written before auth existed still load");
    }

    @Test
    void environmentVariablesOverrideTheFile(@TempDir Path dir) throws IOException {
        AuthConfig auth = load(dir, FILE).withEnvironment(Map.of(
                AuthConfig.ENV_TENANT_ID, "env-tenant",
                AuthConfig.ENV_CLIENT_ID, "env-client",
                AuthConfig.ENV_CLIENT_SECRET, "env-secret",
                AuthConfig.ENV_SCOPE, "api://other/.default",
                AuthConfig.ENV_TOKEN_URL, "http://localhost:18090/blackhole/token")::get);

        assertEquals("env-tenant", auth.getTenantId(), "tenant");
        assertEquals("env-client", auth.getClientId(), "client id");
        assertEquals("env-secret", auth.getClientSecret(), "secret");
        assertEquals("api://other/.default", auth.getScope(), "scope");
        assertEquals("http://localhost:18090/blackhole/token", auth.getTokenUrl(), "token url");
    }

    @Test
    void aBlankOrUnsetVariableKeepsTheFileValue(@TempDir Path dir) throws IOException {
        AuthConfig auth = load(dir, FILE).withEnvironment(Map.of(AuthConfig.ENV_CLIENT_SECRET, "  ")::get);

        assertEquals("file-secret", auth.getClientSecret(), "blank is not an override");
        assertEquals("file-client", auth.getClientId(), "unset is not an override");
    }

    @Test
    void theEnvironmentCanCompleteAnEmptyFile() {
        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(
                AuthConfig.ENV_TENANT_ID, "t", AuthConfig.ENV_CLIENT_ID, "c",
                AuthConfig.ENV_CLIENT_SECRET, "s", AuthConfig.ENV_SCOPE, "api://x/.default")::get);

        assertTrue(auth.isComplete(), "the secret need not be in the file");
    }

    @Test
    void theMissingListNamesTheEnvironmentVariables() {
        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(AuthConfig.ENV_CLIENT_ID, "c")::get);

        String missing = auth.missing().toString();

        assertTrue(missing.contains(AuthConfig.ENV_CLIENT_SECRET) && missing.contains(AuthConfig.ENV_TENANT_ID)
                        && !missing.contains(AuthConfig.ENV_CLIENT_ID),
                "names what to set, and only that: " + missing);
    }

    @Test
    void theSecretIsNotPrinted() {
        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(AuthConfig.ENV_CLIENT_SECRET, "hunter2-secret")::get);

        assertFalse(auth.toString().contains("hunter2-secret"), "toString must not show the secret: " + auth);
        assertTrue(auth.toString().contains("<redacted>"), "but it says one is set: " + auth);
    }

    @Test
    void theTokenEndpointDefaultsFromTheTenant() {
        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(AuthConfig.ENV_TENANT_ID, "contoso.onmicrosoft.com")::get);

        assertEquals(URI.create("https://login.microsoftonline.com/contoso.onmicrosoft.com/oauth2/v2.0/token"), auth.tokenUri(),
                "endpoint of the tenant");
    }

    @Test
    void theTokenUrlOverrideWinsAndNeedsNoTenant() {
        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(
                AuthConfig.ENV_TOKEN_URL, "http://localhost:18090/blackhole/token",
                AuthConfig.ENV_CLIENT_ID, "c", AuthConfig.ENV_CLIENT_SECRET, "s", AuthConfig.ENV_SCOPE, "x")::get);

        assertTrue(auth.isComplete(), "no tenant needed with an override");
        assertEquals(URI.create("http://localhost:18090/blackhole/token"), auth.tokenUri(), "the override");
    }

    @Test
    void aTenantThatCouldChangeTheUrlIsRejected() {
        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(AuthConfig.ENV_TENANT_ID, "evil.example/../x?")::get);

        assertThrows(IllegalArgumentException.class, auth::tokenUri);
    }

    @Test
    void aTokenUrlThatIsNotHttpIsRejected() {
        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(AuthConfig.ENV_TOKEN_URL, "file:///etc/passwd")::get);

        assertThrows(IllegalArgumentException.class, auth::tokenUri);
    }
}
