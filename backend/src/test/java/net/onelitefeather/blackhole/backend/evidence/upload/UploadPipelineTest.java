package net.onelitefeather.blackhole.backend.evidence.upload;

import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadPipeline.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPipelineTest {

    /** A scanner a test controls; records every file it was asked to scan. */
    private static final class FakeScanner implements EvidenceScanner {

        private ScanResult verdict = new Clean();
        private final List<Path> scanned = new ArrayList<>();

        @Override
        public ScanResult scan(Path file) {
            this.scanned.add(file);
            return this.verdict;
        }
    }

    @TempDir
    Path directory;

    private final EvidenceConfig config = new EvidenceConfig();
    private final FakeScanner scanner = new FakeScanner();
    private final UploadPipeline pipeline = new UploadPipeline(this.config, this.scanner);

    private Path upload(byte[] content) throws IOException {
        Path file = Files.createTempFile(this.directory, "up-", ".tmp");
        Files.write(file, content);
        return file;
    }

    private Outcome process(byte[] content, String clientName) throws IOException {
        return this.pipeline.process(upload(content), clientName);
    }

    private void assertRejected(Outcome outcome, EvidenceErrorKind kind) {
        assertNull(outcome.accepted(), "nothing may be accepted");
        assertNotNull(outcome.error(), "expected a " + kind + " rejection");
        assertEquals(kind, outcome.error().kind(), outcome.error().errorMessage());
    }

    private long filesInDirectory() throws IOException {
        try (Stream<Path> files = Files.list(this.directory)) {
            return files.count();
        }
    }

    // ---- type detection ------------------------------------------------------------------------------------

    @Test
    void anExecutableIsUnsupportedWhateverTheClientSaysItIs() throws IOException {
        // The client may send "Content-Type: image/png" and a .png name; the pipeline never sees either.
        assertRejected(process(EvidenceFixtures.executable(), "totally-an-image.png"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void aTextFileIsUnsupported() throws IOException {
        assertRejected(process("hello".getBytes(StandardCharsets.UTF_8), "a.txt"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void aTypeOutsideTheAllowListIsUnsupportedEvenIfRecognised() throws IOException {
        this.config.getFiles().setAllowedTypes(List.of("image/png"));

        assertRejected(process(EvidenceFixtures.mp4(2048), "clip.mp4"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void thePngSignatureWithGarbageBehindItIsUnprocessable() throws IOException {
        byte[] fake = new byte[200];
        System.arraycopy(EvidenceFixtures.pngClaiming(2, 2), 0, fake, 0, 8);

        assertRejected(process(fake, "x.png"), EvidenceErrorKind.UNPROCESSABLE);
    }

    // ---- accepted files ------------------------------------------------------------------------------------

    @Test
    void aPngIsAcceptedWithItsDetectedTypeSizeAndHash() throws IOException {
        Outcome outcome = process(EvidenceFixtures.png(32, 20), "shot.png");

        assertNotNull(outcome.accepted(), () -> String.valueOf(outcome.error()));
        assertEquals("image/png", outcome.accepted().contentType());
        assertEquals(Files.size(outcome.accepted().file()), outcome.accepted().size(), "size is of the stored bytes");
        assertEquals(64, outcome.accepted().sha256().length(), "hex sha-256");
        assertEquals("shot.png", outcome.accepted().displayName());
        BufferedImage decoded = ImageIO.read(outcome.accepted().file().toFile());
        assertEquals(32, decoded.getWidth(), "the re-encoded image is still the same picture");
    }

    @Test
    void aVideoIsStoredAsUploaded() throws IOException {
        byte[] video = EvidenceFixtures.mp4(4096);
        Path spooled = upload(video);

        Outcome outcome = this.pipeline.process(spooled, "clip.mp4");

        assertEquals("video/mp4", outcome.accepted().contentType());
        assertEquals(spooled, outcome.accepted().file(), "videos are not re-encoded");
        assertEquals("video/webm", process(EvidenceFixtures.webm(4096), "c.webm").accepted().contentType());
    }

    @Test
    void theClientFileNameNeverEscapesIntoTheDisplayName() throws IOException {
        Outcome outcome = process(EvidenceFixtures.png(8, 8), "../../etc/passwd");

        assertEquals("passwd", outcome.accepted().displayName(), "only the last path segment survives");
        assertFalse(outcome.accepted().displayName().contains("/"), "no path separators");
        assertFalse(process(EvidenceFixtures.png(8, 8), "..\\..\\windows\\evil.png").accepted().displayName().contains("\\"));
    }

    @Test
    void aMissingFileNameFallsBackToAGenericOne() throws IOException {
        assertEquals("evidence.png", process(EvidenceFixtures.png(8, 8), null).accepted().displayName());
        assertEquals("evidence.jpg", process(EvidenceFixtures.jpeg(8, 8), "///").accepted().displayName());
    }

    // ---- image metadata ------------------------------------------------------------------------------------

    @Test
    void exifDataIsRemovedFromAJpeg() throws IOException {
        byte[] original = EvidenceFixtures.jpegWithExif();
        assertTrue(new String(original, StandardCharsets.ISO_8859_1).contains(EvidenceFixtures.GPS_MARKER), "fixture sanity: the GPS data is there");

        Outcome outcome = process(original, "photo.jpg");

        assertNotNull(outcome.accepted(), () -> String.valueOf(outcome.error()));
        String stored = new String(Files.readAllBytes(outcome.accepted().file()), StandardCharsets.ISO_8859_1);
        assertFalse(stored.contains(EvidenceFixtures.GPS_MARKER), "the GPS coordinates must not be stored");
        assertFalse(stored.contains("Exif"), "no EXIF segment may be stored");
        assertEquals("image/jpeg", outcome.accepted().contentType());
        assertNotNull(ImageIO.read(outcome.accepted().file().toFile()), "and the stored file is still a decodable image");
    }

    @Test
    void thePixelCapIsCheckedFromTheHeaderBeforeAnythingIsDecoded() throws IOException {
        // 10000 x 10000 = 100 million pixels, claimed by a header that carries no pixel data at all:
        // decoding it would fail, so a 413 proves the check happened first.
        Outcome outcome = process(EvidenceFixtures.pngClaiming(10_000, 10_000), "bomb.png");

        assertRejected(outcome, EvidenceErrorKind.PAYLOAD_TOO_LARGE);
        assertTrue(outcome.error().errorMessage().contains("100000000 pixels"), outcome.error().errorMessage());
    }

    // ---- size limits ---------------------------------------------------------------------------------------

    @Test
    void anImageOverTheImageLimitIsTooLarge() throws IOException {
        this.config.getFiles().setMaxImageBytes(1000);

        assertRejected(process(EvidenceFixtures.png(200, 200), "big.png"), EvidenceErrorKind.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aVideoOverTheVideoLimitIsTooLarge() throws IOException {
        this.config.getFiles().setMaxVideoBytes(1000);

        assertRejected(process(EvidenceFixtures.mp4(2000), "big.mp4"), EvidenceErrorKind.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aRejectedImageLeavesNoExtraTemporaryFile() throws IOException {
        Path spooled = upload(EvidenceFixtures.pngClaiming(10_000, 10_000));
        long before = filesInDirectory();

        this.pipeline.process(spooled, "bomb.png");

        assertEquals(before, filesInDirectory(), "the re-encode temp file is removed when the image is refused");
    }

    @Test
    void theSpoolLimitIsTheLargestAllowedType() {
        assertEquals(100L * 1024 * 1024, this.pipeline.maxUploadBytes(), "with video allowed it is the video limit");
        this.config.getFiles().setAllowedTypes(List.of("image/png"));
        assertEquals(10L * 1024 * 1024, this.pipeline.maxUploadBytes(), "without video it is the image limit");
    }

    // ---- scanning ------------------------------------------------------------------------------------------

    @Test
    void aFlaggedFileIsUnprocessableEvenIfItIsAValidImage() throws IOException {
        this.scanner.verdict = new EvidenceScanner.Infected("Eicar-Signature");

        Outcome outcome = process(EvidenceFixtures.png(8, 8), "x.png");

        assertRejected(outcome, EvidenceErrorKind.UNPROCESSABLE);
        assertTrue(outcome.error().errorMessage().contains("Eicar-Signature"), outcome.error().errorMessage());
    }

    @Test
    void aFlaggedFileIsReportedAsMalwareEvenIfItIsNotARecognisedType() throws IOException {
        this.scanner.verdict = new EvidenceScanner.Infected("Eicar-Signature");

        assertRejected(process(EvidenceFixtures.EICAR.getBytes(StandardCharsets.US_ASCII), "eicar.txt"), EvidenceErrorKind.UNPROCESSABLE);
    }

    @Test
    void anUnavailableScannerBlocksTheUpload() throws IOException {
        this.scanner.verdict = new EvidenceScanner.Unavailable("connection refused");

        assertRejected(process(EvidenceFixtures.png(8, 8), "x.png"), EvidenceErrorKind.UNAVAILABLE);
    }

    @Test
    void theScannerSeesTheFileExactlyAsUploaded() throws IOException {
        Path spooled = upload(EvidenceFixtures.jpegWithExif());

        this.pipeline.process(spooled, "x.jpg");

        assertEquals(List.of(spooled), this.scanner.scanned, "scanning happens on the raw upload, before any re-encoding");
    }

    // ---- spooling ------------------------------------------------------------------------------------------

    @Test
    void anUploadWithinTheLimitIsSpooledCompletely() throws IOException {
        byte[] content = new byte[100_000];

        UploadSpooler.Spooled spooled = UploadSpooler.spool(new ByteArrayInputStream(content), this.directory, 100_000);

        assertFalse(spooled.tooLarge());
        assertEquals(100_000, spooled.size());
        assertEquals(100_000, Files.size(spooled.file()));
    }

    @Test
    void anUploadOverTheLimitIsAbortedAndLeavesNoFile() throws IOException {
        long before = filesInDirectory();

        UploadSpooler.Spooled spooled = UploadSpooler.spool(new ByteArrayInputStream(new byte[100_000]), this.directory, 99_999);

        assertTrue(spooled.tooLarge());
        assertNull(spooled.file());
        assertEquals(before, filesInDirectory(), "no partial file remains");
    }
}
