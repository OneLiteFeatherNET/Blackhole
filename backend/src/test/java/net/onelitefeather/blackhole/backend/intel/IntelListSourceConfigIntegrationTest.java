package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binds the list sources from the packaged application.yml. A context needs PostgreSQL (the startup listeners of the
 * list reloader need the store), so this runs against the shared containers.
 */
@Tag("integration")
class IntelListSourceConfigIntegrationTest extends PostgresIntegrationTest {

    private static Map<String, IntelListSourceConfig> sources(ApplicationContext context) {
        return context.getBeansOfType(IntelListSourceConfig.class).stream()
                .collect(Collectors.toMap(IntelListSourceConfig::getKey, source -> source));
    }

    @Test
    void theTwoX4BnetSourcesExistWithTheirKindsAndAreDisabledByDefault() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            Map<String, IntelListSourceConfig> sources = sources(context);

            assertEquals(IntelClass.VPN, sources.get("x4bnet-vpn").getKind(), "the VPN list is of kind VPN");
            assertEquals(IntelClass.HOSTING, sources.get("x4bnet-datacenter").getKind(), "the datacenter list is of kind HOSTING");
            assertFalse(sources.get("x4bnet-vpn").isEnabled(), "a source stays off until an admin checks a refresh");
            assertFalse(sources.get("x4bnet-datacenter").isEnabled(), "a source stays off until an admin checks a refresh");
        }
    }

    @Test
    void theDefaultUrlsAreHttpsAndTheAttributionNamesTheMitLicence() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            Map<String, IntelListSourceConfig> sources = sources(context);

            assertTrue(sources.get("x4bnet-vpn").getUrl().startsWith("https://"), "the default VPN URL is HTTPS");
            assertTrue(sources.get("x4bnet-datacenter").getUrl().startsWith("https://"), "the default datacenter URL is HTTPS");
            assertTrue(sources.get("x4bnet-vpn").getAttribution().contains("MIT"), "the attribution names the MIT licence");
        }
    }
}
