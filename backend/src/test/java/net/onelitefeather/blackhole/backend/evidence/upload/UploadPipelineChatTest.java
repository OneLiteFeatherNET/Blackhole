package net.onelitefeather.blackhole.backend.evidence.upload;

import net.onelitefeather.blackhole.backend.evidence.ChatAttachmentConfig;
import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner;
import net.onelitefeather.blackhole.backend.evidence.upload.UploadPipeline.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CHAT upload profile: scan first, sniff by content, then the step for the type. Fakes only, no network, no clock. */
class UploadPipelineChatTest {

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
    private final ChatAttachmentConfig chat = new ChatAttachmentConfig();
    private final FakeScanner scanner = new FakeScanner();
    private final UploadPipeline pipeline = new UploadPipeline(this.config, this.chat, this.scanner);

    private Path upload(byte[] content) throws IOException {
        Path file = Files.createTempFile(this.directory, "up-", ".tmp");
        Files.write(file, content);
        return file;
    }

    private Outcome chat(byte[] content, String clientName) throws IOException {
        return this.pipeline.process(upload(content), clientName, UploadProfile.CHAT);
    }

    private void assertRejected(Outcome outcome, EvidenceErrorKind kind) {
        assertNull(outcome.accepted(), "nothing may be accepted");
        assertNotNull(outcome.error(), "expected a " + kind + " rejection");
        assertEquals(kind, outcome.error().kind(), outcome.error().errorMessage());
    }

    private Outcome assertAccepted(Outcome outcome) {
        assertNotNull(outcome.accepted(), () -> String.valueOf(outcome.error()));
        return outcome;
    }

    // ---- accepted types ------------------------------------------------------------------------------------

    @Test
    void aPngIsAcceptedAndReportsItsSize() throws IOException {
        Outcome outcome = assertAccepted(chat(EvidenceFixtures.png(32, 20), "shot.png"));

        assertEquals("image/png", outcome.accepted().contentType());
        assertEquals(32, outcome.accepted().width(), "width of the re-encoded image");
        assertEquals(20, outcome.accepted().height(), "height of the re-encoded image");
    }

    @Test
    void aJpegIsAcceptedAsImageJpeg() throws IOException {
        assertEquals("image/jpeg", assertAccepted(chat(EvidenceFixtures.jpeg(16, 16), "a.jpg")).accepted().contentType());
    }

    @Test
    void aWebpIsAcceptedWithItsCanvas() throws IOException {
        Outcome outcome = assertAccepted(chat(ChatFixtures.webp(40, 30), "a.webp"));

        assertEquals("image/webp", outcome.accepted().contentType());
        assertEquals(40, outcome.accepted().width());
        assertEquals(30, outcome.accepted().height());
    }

    @Test
    void aPdfIsAcceptedAsDownloadType() throws IOException {
        Outcome outcome = assertAccepted(chat(ChatFixtures.pdf("/Pages 2 0 R"), "report.pdf"));

        assertEquals("application/pdf", outcome.accepted().contentType());
        assertNull(outcome.accepted().width(), "only images have a size in pixels");
    }

    @Test
    void aTextFileIsAcceptedAsTextPlain() throws IOException {
        assertEquals("text/plain", assertAccepted(chat(ChatFixtures.utf8("my log\nline two"), "log.txt")).accepted().contentType());
    }

    // ---- content decides, never the name -------------------------------------------------------------------

    @Test
    void aPngCalledPdfIsStillAPng() throws IOException {
        assertEquals("image/png", assertAccepted(chat(EvidenceFixtures.png(8, 8), "a.pdf")).accepted().contentType());
    }

    @Test
    void aVideoIsUnsupported() throws IOException {
        assertRejected(chat(EvidenceFixtures.mp4(4096), "clip.mp4"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
        assertRejected(chat(EvidenceFixtures.webm(4096), "clip.webm"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void anExecutableIsUnsupported() throws IOException {
        assertRejected(chat(EvidenceFixtures.executable(), "photo.png"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void anArchiveIsUnsupported() throws IOException {
        assertRejected(chat(new byte[]{'P', 'K', 3, 4, 0, 0, 8, 0, 0, 0, (byte) 0x9F}, "a.zip"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void anSvgIsUnsupported() throws IOException {
        assertRejected(chat(ChatFixtures.utf8("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>"), "a.svg"),
                EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void aTypeTakenOffTheAllowListIsUnsupportedEvenIfRecognised() throws IOException {
        this.chat.setAllowedTypes(List.of("image/png"));

        assertRejected(chat(ChatFixtures.pdf("/Pages 2 0 R"), "a.pdf"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
        assertRejected(chat(ChatFixtures.utf8("text"), "a.txt"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void anEmptyFileIsRefused() throws IOException {
        assertRejected(chat(new byte[0], "empty.txt"), EvidenceErrorKind.UNPROCESSABLE);
    }

    // ---- polyglots -----------------------------------------------------------------------------------------

    @Test
    void aPngWithAPdfBehindItLosesThePdf() throws IOException {
        byte[] png = EvidenceFixtures.png(8, 8);
        byte[] pdf = ChatFixtures.pdf("/JavaScript 1 0 R");
        byte[] polyglot = new byte[png.length + pdf.length];
        System.arraycopy(png, 0, polyglot, 0, png.length);
        System.arraycopy(pdf, 0, polyglot, png.length, pdf.length);

        Outcome outcome = assertAccepted(chat(polyglot, "x.png"));

        assertEquals("image/png", outcome.accepted().contentType(), "the signature at offset 0 decides");
        assertFalse(new String(Files.readAllBytes(outcome.accepted().file()), StandardCharsets.ISO_8859_1).contains("%PDF-"),
                "the re-encode keeps the picture and drops everything behind it");
    }

    @Test
    void aWebpWithAPdfBehindItIsRefused() throws IOException {
        byte[] webp = ChatFixtures.webp(8, 8);
        byte[] pdf = ChatFixtures.pdf("");
        byte[] polyglot = new byte[webp.length + pdf.length];
        System.arraycopy(webp, 0, polyglot, 0, webp.length);
        System.arraycopy(pdf, 0, polyglot, webp.length, pdf.length);

        assertRejected(chat(polyglot, "x.webp"), EvidenceErrorKind.UNPROCESSABLE);
    }

    // ---- metadata ------------------------------------------------------------------------------------------

    @Test
    void exifIsRemovedFromAJpeg() throws IOException {
        Outcome outcome = assertAccepted(chat(EvidenceFixtures.jpegWithExif(), "photo.jpg"));

        String stored = new String(Files.readAllBytes(outcome.accepted().file()), StandardCharsets.ISO_8859_1);
        assertFalse(stored.contains(EvidenceFixtures.GPS_MARKER), "the GPS coordinates must not be stored");
        assertFalse(stored.contains("Exif"), "no EXIF segment may be stored");
    }

    @Test
    void exifAndXmpAreRemovedFromAWebp() throws IOException {
        Outcome outcome = assertAccepted(chat(ChatFixtures.webpWithMetadata(16, 16), "photo.webp"));

        String stored = new String(Files.readAllBytes(outcome.accepted().file()), StandardCharsets.ISO_8859_1);
        assertFalse(stored.contains(ChatFixtures.GPS_MARKER), "the GPS coordinates must not be stored");
        assertEquals(Files.size(outcome.accepted().file()), outcome.accepted().size(), "the size is of the stored bytes");
    }

    @Test
    void aWebpWithTrailingBytesIsUnprocessable() throws IOException {
        assertRejected(chat(ChatFixtures.withTrailing(ChatFixtures.webp(8, 8), 10), "x.webp"), EvidenceErrorKind.UNPROCESSABLE);
    }

    @Test
    void anAnimatedWebpIsUnsupported() throws IOException {
        byte[] animated = ChatFixtures.riff(ChatFixtures.vp8x(0x02, 16, 16), ChatFixtures.chunk("ANIM", new byte[6]));

        assertRejected(chat(animated, "x.webp"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void anImageOverThePixelCapIsTooLarge() throws IOException {
        assertRejected(chat(ChatFixtures.webp(10_000, 10_000), "bomb.webp"), EvidenceErrorKind.PAYLOAD_TOO_LARGE);
        assertRejected(chat(EvidenceFixtures.pngClaiming(10_000, 10_000), "bomb.png"), EvidenceErrorKind.PAYLOAD_TOO_LARGE);
    }

    // ---- PDF and text --------------------------------------------------------------------------------------

    @Test
    void aPdfWithScriptIsActiveContent() throws IOException {
        assertRejected(chat(ChatFixtures.pdf("/OpenAction << /S /JavaScript /JS (app.alert(1)) >>"), "a.pdf"),
                EvidenceErrorKind.FILE_ACTIVE_CONTENT);
    }

    @Test
    void anEncryptedPdfIsActiveContent() throws IOException {
        assertRejected(chat(ChatFixtures.pdf("/Encrypt 7 0 R"), "a.pdf"), EvidenceErrorKind.FILE_ACTIVE_CONTENT);
    }

    @Test
    void aTextThatIsNotUtf8IsUnsupported() throws IOException {
        assertRejected(chat(new byte[]{'a', (byte) 0xC3, 0x28}, "a.txt"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    // ---- scanning ------------------------------------------------------------------------------------------

    @Test
    void aFlaggedFileIsFlaggedWithoutTheSignatureName() throws IOException {
        this.scanner.verdict = new EvidenceScanner.Infected("Eicar-Signature");

        Outcome outcome = chat(EvidenceFixtures.png(8, 8), "x.png");

        assertRejected(outcome, EvidenceErrorKind.FILE_FLAGGED);
        assertFalse(outcome.error().errorMessage().contains("Eicar"), "the signature name stays inside: " + outcome.error().errorMessage());
    }

    @Test
    void aFlaggedFileIsFlaggedWhateverTypeItIs() throws IOException {
        this.scanner.verdict = new EvidenceScanner.Infected("Eicar-Signature");

        assertRejected(chat(EvidenceFixtures.EICAR.getBytes(StandardCharsets.US_ASCII), "eicar.txt"), EvidenceErrorKind.FILE_FLAGGED);
        assertRejected(chat(EvidenceFixtures.mp4(2048), "clip.mp4"), EvidenceErrorKind.FILE_FLAGGED);
    }

    @Test
    void anUnavailableScannerBlocksTheUpload() throws IOException {
        this.scanner.verdict = new EvidenceScanner.Unavailable("connection refused");

        assertRejected(chat(EvidenceFixtures.png(8, 8), "x.png"), EvidenceErrorKind.UNAVAILABLE);
    }

    @Test
    void theScannerSeesTheFileExactlyAsUploaded() throws IOException {
        Path spooled = upload(EvidenceFixtures.jpegWithExif());

        this.pipeline.process(spooled, "x.jpg", UploadProfile.CHAT);

        assertEquals(List.of(spooled), this.scanner.scanned, "scanning is the first step, on the raw upload");
    }

    // ---- size ----------------------------------------------------------------------------------------------

    @Test
    void aFileOverTheLimitIsTooLarge() throws IOException {
        this.chat.setMaxBytes(1000);

        assertRejected(chat(ChatFixtures.utf8("x".repeat(1001)), "big.txt"), EvidenceErrorKind.PAYLOAD_TOO_LARGE);
        assertAccepted(chat(ChatFixtures.utf8("x".repeat(1000)), "ok.txt"));
    }

    @Test
    void theSpoolLimitOfTheChatProfileIsItsOwn() {
        assertEquals(10L * 1024 * 1024, this.pipeline.maxUploadBytes(UploadProfile.CHAT));
        this.chat.setMaxBytes(2048);
        assertEquals(2048, this.pipeline.maxUploadBytes(UploadProfile.CHAT));
        assertEquals(100L * 1024 * 1024, this.pipeline.maxUploadBytes(UploadProfile.EVIDENCE), "evidence keeps the video limit");
    }

    // ---- names ---------------------------------------------------------------------------------------------

    @Test
    void aNameShapedLikeAnIdentifierBecomesAGenericOne() throws IOException {
        assertEquals("attachment.png", assertAccepted(chat(EvidenceFixtures.png(8, 8), "123e4567-e89b-12d3-a456-426614174000.png")).accepted().displayName());
        assertEquals("attachment.png", assertAccepted(chat(EvidenceFixtures.png(8, 8), "from 192.168.1.20.png")).accepted().displayName());
        assertEquals("attachment.png", assertAccepted(chat(EvidenceFixtures.png(8, 8), "my ban #A1B2C3D4.png")).accepted().displayName());
    }

    @Test
    void anOrdinaryNameIsKeptAndAMissingOneBecomesAGenericOne() throws IOException {
        assertEquals("shot.png", assertAccepted(chat(EvidenceFixtures.png(8, 8), "shot.png")).accepted().displayName());
        assertEquals("attachment.pdf", assertAccepted(chat(ChatFixtures.pdf(""), null)).accepted().displayName());
    }

    // ---- the evidence profile is untouched -----------------------------------------------------------------

    @Test
    void theEvidenceProfileStillRefusesWhatOnlyChatAccepts() throws IOException {
        this.config.getFiles().setAllowedTypes(List.of("image/png", "image/webp", "application/pdf", "video/mp4"));

        assertRejected(this.pipeline.process(upload(ChatFixtures.webp(8, 8)), "a.webp"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
        assertRejected(this.pipeline.process(upload(ChatFixtures.pdf("")), "a.pdf"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
        assertRejected(this.pipeline.process(upload(ChatFixtures.utf8("text")), "a.txt"), EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void theEvidenceProfileStillAcceptsVideoThatChatRefuses() throws IOException {
        Outcome outcome = this.pipeline.process(upload(EvidenceFixtures.mp4(4096)), "clip.mp4");

        assertTrue(outcome.accepted() != null && outcome.accepted().contentType().equals("video/mp4"), () -> String.valueOf(outcome.error()));
    }
}
