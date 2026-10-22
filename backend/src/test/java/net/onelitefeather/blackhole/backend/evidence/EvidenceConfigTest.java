package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceConfigTest {

    /**
     * Starts the application context with the database wired to an address that is never connected
     * to (lazy pool, no schema checks), so only the configuration binding is under test: no
     * containers, no network.
     */
    private static ApplicationContext context(Map<String, Object> overrides) {
        Map<String, Object> properties = new java.util.HashMap<>(Map.of(
                "flyway.datasources.default.enabled", false,
                "datasources.default.url", "jdbc:postgresql://127.0.0.1:1/never-connected",
                "datasources.default.initialization-fail-timeout", -1,
                "jpa.default.properties.hibernate.hbm2ddl.auto", "none",
                "jpa.default.properties.hibernate.boot.allow_jdbc_metadata_access", false,
                "jpa.default.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                "micronaut.server.port", -1
        ));
        properties.putAll(overrides);
        return ApplicationContext.builder().properties(properties).build().start();
    }

    @Test
    void theDocumentedDefaultsBind() {
        try (ApplicationContext context = context(Map.of())) {
            EvidenceConfig config = context.getBean(EvidenceConfig.class);

            assertEquals(32_768, config.getMaxPayloadBytes(), "default payload limit");
            assertEquals(50, config.getMaxChatLines(), "default chat line limit");
            assertEquals(Duration.ofDays(1), config.getPendingTtl(), "pending evidence expires after P1D");
            assertNull(config.getAuditRetention(), "audit rows are kept indefinitely unless configured");
            assertEquals(Duration.ofHours(1), config.getHousekeeping().getInterval(), "sweep interval");
            assertEquals(EvidenceConfig.Storage.S3, config.getFiles().getStorage(), "S3 is the default storage");
            assertEquals(10L * 1024 * 1024, config.getFiles().getMaxImageBytes(), "image limit is 10 MiB");
            assertEquals(100L * 1024 * 1024, config.getFiles().getMaxVideoBytes(), "video limit is 100 MiB");
            assertEquals(50_000_000L, config.getFiles().getMaxImagePixels(), "pixel cap");
            assertEquals(List.of("image/png", "image/jpeg", "video/mp4", "video/webm"), config.getFiles().getAllowedTypes());
            assertTrue(config.getScan().isEnabled(), "scanning fails closed, so it is on by default");
            assertEquals(3310, config.getScan().getClamd().getPort(), "clamd default port");
        }
    }

    @Test
    void anOverriddenValueIsBound() {
        try (ApplicationContext context = context(Map.of(
                "blackhole.evidence.pending-ttl", "PT2H",
                "blackhole.evidence.audit-retention", "P365D",
                "blackhole.evidence.files.storage", "local",
                "blackhole.evidence.scan.enabled", false))) {
            EvidenceConfig config = context.getBean(EvidenceConfig.class);

            assertEquals(Duration.ofHours(2), config.getPendingTtl());
            assertEquals(Duration.ofDays(365), config.getAuditRetention());
            assertEquals(EvidenceConfig.Storage.LOCAL, config.getFiles().getStorage());
            assertEquals(false, config.getScan().isEnabled());
        }
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void anUnparsableDurationFailsFast() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> context(Map.of("blackhole.evidence.pending-ttl", "tomorrow")).close(),
                "a duration that is not ISO-8601 must not be silently replaced by a default");
        assertTrue(messages(failure).contains("pending-ttl"), "the message names the property: " + messages(failure));
    }

    @Test
    void aNonPositiveDurationFailsFast() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> context(Map.of("blackhole.evidence.housekeeping.interval", "PT0S")).close(),
                "a zero sweep interval must be rejected when the application starts");
        assertTrue(messages(failure).contains("housekeeping.interval"), "the message names the property: " + messages(failure));
    }
}
