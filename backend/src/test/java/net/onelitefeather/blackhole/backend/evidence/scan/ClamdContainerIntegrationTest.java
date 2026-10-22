package net.onelitefeather.blackhole.backend.evidence.scan;

import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter against a real ClamAV daemon, with the EICAR test string. Opt-in (see
 * {@link ClamAvSupport}) because the daemon downloads its signatures on first start.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ClamdContainerIntegrationTest {

    private static GenericContainer<?> clamav;

    @TempDir
    Path directory;

    @BeforeAll
    static void startClamav() {
        Assumptions.assumeTrue(ClamAvSupport.enabled(), "set " + ClamAvSupport.OPT_IN + "=true to run the ClamAV container tests");
        clamav = ClamAvSupport.container();
        clamav.start();
    }

    @AfterAll
    static void stopClamav() {
        if (clamav != null) {
            clamav.stop();
        }
    }

    private ClamdScanner scanner() {
        EvidenceConfig config = new EvidenceConfig();
        config.getScan().getClamd().setHost(clamav.getHost());
        config.getScan().getClamd().setPort(clamav.getMappedPort(3310));
        return new ClamdScanner(config);
    }

    private Path file(byte[] content) throws IOException {
        Path file = Files.createTempFile(this.directory, "scan-", ".bin");
        Files.write(file, content);
        return file;
    }

    @Test
    void anInnocentFileIsClean() throws IOException {
        EvidenceScanner.ScanResult result = scanner().scan(file("just some text".getBytes(StandardCharsets.UTF_8)));

        assertInstanceOf(EvidenceScanner.Clean.class, result, String.valueOf(result));
    }

    @Test
    void theEicarTestStringIsFlagged() throws IOException {
        EvidenceScanner.ScanResult result = scanner().scan(file(EvidenceFixtures.EICAR.getBytes(StandardCharsets.US_ASCII)));

        EvidenceScanner.Infected infected = assertInstanceOf(EvidenceScanner.Infected.class, result, String.valueOf(result));
        assertTrue(infected.signature().toLowerCase().contains("eicar"), infected.signature());
    }
}
