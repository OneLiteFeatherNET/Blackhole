package net.onelitefeather.blackhole.backend.intel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.backend.intel.IpEnricher.IpEnrichment;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Offline lookups over the small MMDB fixtures under src/test/resources/geoip; nothing is downloaded. */
class GeoIpReaderTest {

    private final TestClock clock = new TestClock();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureLog() {
        this.logger = (Logger) LoggerFactory.getLogger(GeoIpReader.class);
        this.logger.setLevel(Level.DEBUG);
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void releaseLog() {
        this.logger.detachAppender(this.appender);
    }

    private static Path fixture(String name) {
        try {
            return Path.of(GeoIpReaderTest.class.getResource("/geoip/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("fixture " + name + " is not a file", e);
        }
    }

    private static Path missing() {
        return Path.of("/nonexistent-geoip-dir/absent.mmdb");
    }

    @Test
    void aKnownAddressResolvesToItsAsnAndCountry() {
        GeoIpReader reader = new GeoIpReader(clock, fixture("asn-test.mmdb").toString(), fixture("country-test.mmdb").toString());

        IpEnrichment enrichment = reader.enrich("192.0.2.10");

        assertEquals(64496L, enrichment.asn());
        assertEquals("Example Transit", enrichment.asOrg());
        assertEquals("DE", enrichment.countryCode());
    }

    @Test
    void anAddressNotInTheFilesLeavesTheFieldsEmpty() {
        GeoIpReader reader = new GeoIpReader(clock, fixture("asn-test.mmdb").toString(), fixture("country-test.mmdb").toString());

        assertEquals(IpEnrichment.NONE, reader.enrich("203.0.113.7"));
    }

    @Test
    void aMissingDatabaseFileLeavesTheEnrichmentEmptyWithoutThrowing() {
        GeoIpReader reader = new GeoIpReader(clock, missing().toString(), missing().toString());

        IpEnrichment enrichment = reader.enrich("192.0.2.10");

        assertNull(enrichment.asn());
        assertNull(enrichment.countryCode());
    }

    @Test
    void aMissingDatabaseFileIsLoggedOncePerHour() {
        GeoIpReader reader = new GeoIpReader(clock, missing().toString(), missing().toString());

        reader.enrich("192.0.2.10");
        reader.enrich("192.0.2.11");
        List<ILoggingEvent> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(2, warnings.size(), "one warning per missing file for the first check");

        clock.advance(Duration.ofMinutes(30));
        reader.enrich("192.0.2.12");
        assertEquals(2, appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count(),
                "no new warning inside the hour");
    }

    @Test
    void aNonAddressIsNeverLookedUp() {
        GeoIpReader reader = new GeoIpReader(clock, fixture("asn-test.mmdb").toString(), fixture("country-test.mmdb").toString());

        assertEquals(IpEnrichment.NONE, reader.enrich("example.com"));
    }
}
