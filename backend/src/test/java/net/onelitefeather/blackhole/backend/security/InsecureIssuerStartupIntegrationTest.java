package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup guard on a real application context: the default (mock server) issuer must not reach production. */
@Tag("integration")
class InsecureIssuerStartupIntegrationTest extends PostgresIntegrationTest {

    private static ApplicationContext start(String environment, Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>(contextProperties());
        properties.put("micronaut.server.port", -1);
        // A production context needs a real Ban ID key (the development default is refused there).
        properties.put("blackhole.banid.key", net.onelitefeather.blackhole.backend.support.TestKeys.randomBase64());
        properties.put("micronaut.security.token.jwt.claims-validators.audience", "00000000-0000-4000-8000-000000000001");
        properties.put("blackhole.security.tenant-id", "00000000-0000-4000-8000-000000000002");
        properties.putAll(extra);
        return ApplicationContext.builder().deduceEnvironment(false).environments(environment).properties(properties).start();
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void aProductionContextWithTheLocalDefaultIssuerFailsToStart() {
        Throwable failure = assertThrows(Throwable.class, () -> start("prod", Map.of()), "prod with http://localhost issuer");

        assertTrue(messages(failure).contains("BLACKHOLE_AUTH_ISSUER"), "the failure names the setting: " + messages(failure));
    }

    @Test
    void aProductionContextWithTheExplicitOverrideStarts() {
        assertDoesNotThrow(() -> start("prod", Map.of("blackhole.security.allow-insecure-issuer", true)).close(),
                "prod with the override");
    }

    @Test
    void aProductionContextWithASecureIssuerStarts() {
        Map<String, Object> secure = Map.of("micronaut.security.token.jwt.claims-validators.issuer", "https://login.microsoftonline.com/tenant/v2.0");

        assertDoesNotThrow(() -> start("prod", secure).close(), "prod with an https issuer");
    }

    @Test
    void aDevContextWithTheLocalDefaultIssuerStarts() {
        assertDoesNotThrow(() -> start("dev", Map.of()).close(), "dev with http://localhost issuer");
    }
}
