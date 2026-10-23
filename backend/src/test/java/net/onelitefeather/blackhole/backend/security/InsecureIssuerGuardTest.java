package net.onelitefeather.blackhole.backend.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsecureIssuerGuardTest {

    @ParameterizedTest(name = "{0} with {1} refuses to start")
    @CsvSource({
            "http://localhost:18090/blackhole, prod",
            "http://localhost:18090/blackhole, ''",
            "http://127.0.0.1:8080/issuer, k8s",
            "http://idp.example.org/issuer, prod",
            "https://localhost/issuer, prod",
            "'', prod"
    })
    void aLocalOrPlainHttpIssuerIsRefusedOutsideDevAndTest(String issuer, String environment) {
        Set<String> environments = environment.isEmpty() ? Set.of() : Set.of(environment);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> InsecureIssuerGuard.check(issuer, environments, false), "issuer " + issuer);

        assertTrue(failure.getMessage().contains("BLACKHOLE_AUTH_ISSUER"), "the message names the setting: " + failure.getMessage());
    }

    @ParameterizedTest(name = "{0} with {1} starts")
    @CsvSource({
            "http://localhost:18090/blackhole, dev",
            "http://localhost:18090/blackhole, test",
            "http://localhost:18090/blackhole, 'prod,dev'",
            "https://login.microsoftonline.com/tenant/v2.0, prod",
            "https://login.microsoftonline.com/tenant/v2.0, ''"
    })
    void aSecureIssuerOrALocalEnvironmentStarts(String issuer, String environments) {
        Set<String> active = environments.isEmpty() ? Set.of() : Set.of(environments.split(","));

        assertDoesNotThrow(() -> InsecureIssuerGuard.check(issuer, active, false), "issuer " + issuer + " in " + active);
    }

    @ParameterizedTest(name = "the override lets {0} start in prod")
    @CsvSource({"http://localhost:18090/blackhole", "http://idp.internal/issuer"})
    void theExplicitOverrideAllowsAnInsecureIssuer(String issuer) {
        assertDoesNotThrow(() -> InsecureIssuerGuard.check(issuer, Set.of("prod"), true), "issuer " + issuer);
    }
}
