package net.onelitefeather.blackhole.backend.evidence.scan;

import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner.Clean;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner.Infected;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner.ScanResult;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner.Unavailable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clamd INSTREAM adapter against a protocol-level stub on a loopback port. */
class ClamdScannerTest {

    @TempDir
    Path directory;

    private ClamdScanner scannerOn(int port) {
        EvidenceConfig config = new EvidenceConfig();
        config.getScan().getClamd().setHost("127.0.0.1");
        config.getScan().getClamd().setPort(port);
        config.getScan().getClamd().setTimeout(Duration.ofSeconds(5));
        return new ClamdScanner(config);
    }

    private Path file(byte[] content) throws IOException {
        Path file = Files.createTempFile(this.directory, "scan-", ".bin");
        Files.write(file, content);
        return file;
    }

    @Test
    void aCleanVerdictIsClean() throws Exception {
        try (StubClamd clamd = new StubClamd("stream: OK")) {
            ScanResult result = scannerOn(clamd.port()).scan(file("hello".getBytes(StandardCharsets.UTF_8)));

            assertInstanceOf(Clean.class, result, String.valueOf(result));
        }
    }

    @Test
    void aFoundVerdictNamesTheSignature() throws Exception {
        try (StubClamd clamd = new StubClamd("stream: Eicar-Signature FOUND")) {
            ScanResult result = scannerOn(clamd.port()).scan(file(EvidenceFixtures.EICAR.getBytes(StandardCharsets.US_ASCII)));

            Infected infected = assertInstanceOf(Infected.class, result, String.valueOf(result));
            assertEquals("Eicar-Signature", infected.signature());
        }
    }

    @Test
    void theFileIsStreamedWithTheInstreamCommandAndFraming() throws Exception {
        byte[] content = new byte[200_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        try (StubClamd clamd = new StubClamd("stream: OK")) {
            scannerOn(clamd.port()).scan(file(content));
            clamd.awaitServed();

            assertEquals("zINSTREAM", clamd.command(), "the command");
            assertArrayEquals(content, clamd.streamed(), "all bytes arrive, in order, across several chunks");
        }
    }

    @Test
    void anErrorReplyIsUnavailableNotClean() throws Exception {
        try (StubClamd clamd = new StubClamd("INSTREAM size limit exceeded. ERROR")) {
            ScanResult result = scannerOn(clamd.port()).scan(file(new byte[10]));

            Unavailable unavailable = assertInstanceOf(Unavailable.class, result, String.valueOf(result));
            assertTrue(unavailable.reason().contains("size limit"), unavailable.reason());
        }
    }

    @Test
    void anEmptyReplyIsUnavailableNotClean() throws Exception {
        try (StubClamd clamd = new StubClamd("")) {
            assertInstanceOf(Unavailable.class, scannerOn(clamd.port()).scan(file(new byte[10])),
                    "no verdict must never be read as clean");
        }
    }

    @Test
    void aRefusedConnectionIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        ScanResult result = scannerOn(closedPort).scan(file(new byte[10]));

        assertInstanceOf(Unavailable.class, result, "a scanner that cannot be reached fails closed");
    }
}
