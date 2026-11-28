package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outside dev and test the audience must be the API app client id GUID and a tenant id must be set. */
@Tag("integration")
class StartupRequirementsTest extends PostgresIntegrationTest {

    private static final String AUDIENCE_KEY = "micronaut.security.token.jwt.claims-validators.audience";
    private static final String GUID = "774f6ffd-1111-4222-8333-444455556666";
    private static final String TENANT = "11111111-2222-4333-8444-555566667777";

    /** Starts with a valid challenge key, so only the setting under test can fail the startup. */
    private static ApplicationContext start(String environment, String audience, String tenant) {
        return start(environment, audience, tenant, net.onelitefeather.blackhole.backend.support.TestKeys.randomBase64());
    }

    private static ApplicationContext start(String environment, String audience, String tenant, String challengeKey) {
        Map<String, Object> properties = new HashMap<>(contextProperties());
        properties.put("micronaut.server.port", -1);
        properties.put("blackhole.banid.key", net.onelitefeather.blackhole.backend.support.TestKeys.randomBase64());
        properties.put("micronaut.security.token.jwt.claims-validators.issuer", "https://login.microsoftonline.com/tenant/v2.0");
        properties.put(AUDIENCE_KEY, audience);
        properties.put("blackhole.security.tenant-id", tenant);
        // The challenge runs as in a deployment: on, with its own key (an empty key is refused outside dev and test).
        properties.put("blackhole.public.challenge.enabled", true);
        properties.put("blackhole.public.challenge.key", challengeKey);
        return ApplicationContext.builder().deduceEnvironment(false).environments(environment).properties(properties).start();
    }

    @Test
    void aMissingChallengeKeyOutsideDevFailsToStartAndNamesTheSetting() {
        Throwable failure = assertThrows(Throwable.class, () -> start("prod", GUID, TENANT, ""), "empty challenge key");

        assertTrue(messages(failure).contains("BLACKHOLE_PUBLIC_CHALLENGE_KEY"), "the failure names the challenge key: " + messages(failure));
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "blackhole-api", "api://774f6ffd-1111-4222-8333-444455556666"})
    void anInvalidAudienceOutsideDevFailsToStartAndNamesTheSetting(String audience) {
        Throwable failure = assertThrows(Throwable.class, () -> start("prod", audience, TENANT), "audience '" + audience + "'");

        assertTrue(messages(failure).contains("BLACKHOLE_AUTH_AUDIENCE"), "the failure names the audience setting: " + messages(failure));
    }

    @Test
    void aMissingTenantIdOutsideDevFailsToStartAndNamesTheSetting() {
        Throwable failure = assertThrows(Throwable.class, () -> start("prod", GUID, ""), "empty tenant id");

        assertTrue(messages(failure).contains("BLACKHOLE_AUTH_TENANT_ID"), "the failure names the tenant setting: " + messages(failure));
    }

    @Test
    void aGuidAudienceWithATenantIdStartsOutsideDev() {
        assertDoesNotThrow(() -> start("prod", GUID, TENANT).close(), "prod with a GUID audience and a tenant id");
    }

    @Test
    void theLocalDefaultsStillStartInDev() {
        assertDoesNotThrow(() -> start("dev", "blackhole-api", "").close(), "dev with the placeholder audience");
    }
}
