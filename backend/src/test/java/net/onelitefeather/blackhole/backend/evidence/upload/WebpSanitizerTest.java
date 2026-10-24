package net.onelitefeather.blackhole.backend.evidence.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebpSanitizerTest {

    private static final long CAP = 1_000_000L;

    @TempDir
    Path directory;

    private WebpSanitizer.Result sanitize(byte[] webp) throws IOException {
        Path input = this.directory.resolve("in.webp");
        Files.write(input, webp);
        return WebpSanitizer.sanitize(input, this.directory.resolve("out.webp"), CAP);
    }

    @Test
    void aPlainLosslessFileIsKeptAndItsCanvasIsRead() throws IOException {
        WebpSanitizer.Sanitized result = assertInstanceOf(WebpSanitizer.Sanitized.class, sanitize(ChatFixtures.webp(300, 200)));

        assertEquals(300, result.width(), "canvas width");
        assertEquals(200, result.height(), "canvas height");
        assertEquals(ChatFixtures.webp(300, 200).length, Files.size(result.output()), "nothing to strip, nothing changes");
    }

    @Test
    void exifAndXmpAreRemovedAndTheirFlagsCleared() throws IOException {
        byte[] original = ChatFixtures.webpWithMetadata(64, 48);
        assertTrue(new String(original, StandardCharsets.ISO_8859_1).contains(ChatFixtures.GPS_MARKER), "fixture sanity: the GPS data is there");

        WebpSanitizer.Sanitized result = assertInstanceOf(WebpSanitizer.Sanitized.class, sanitize(original));

        byte[] stored = Files.readAllBytes(result.output());
        String text = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(ChatFixtures.GPS_MARKER), "the GPS coordinates must not be stored");
        assertFalse(text.contains("EXIF") || text.contains("XMP "), "no EXIF or XMP chunk may be stored");
        assertEquals(0x10, stored[20] & 0xFF, "the VP8X flags keep alpha and drop the EXIF and XMP bits");
        assertEquals(stored.length - 8, (stored[4] & 0xFF) | (stored[5] & 0xFF) << 8 | (stored[6] & 0xFF) << 16 | (stored[7] & 0xFF) << 24,
                "the RIFF size is rewritten to the new length");
        assertInstanceOf(WebpSanitizer.Sanitized.class, WebpSanitizer.sanitize(result.output(), this.directory.resolve("again.webp"), CAP),
                "the stripped file is itself a valid WebP");
    }

    @Test
    void bytesAfterTheRiffLengthAreRefused() throws IOException {
        assertInstanceOf(WebpSanitizer.Invalid.class, sanitize(ChatFixtures.withTrailing(ChatFixtures.webp(8, 8), 16)),
                "trailing bytes are where a polyglot hides its second format");
    }

    @Test
    void aFileShorterThanItsRiffLengthIsRefused() throws IOException {
        byte[] webp = ChatFixtures.webp(8, 8);
        byte[] cut = new byte[webp.length - 4];
        System.arraycopy(webp, 0, cut, 0, cut.length);

        assertInstanceOf(WebpSanitizer.Invalid.class, sanitize(cut));
    }

    @Test
    void aChunkThatEndsBehindTheFileIsRefused() throws IOException {
        byte[] webp = ChatFixtures.webp(8, 8);
        webp[16] = (byte) 0xFF;
        webp[17] = (byte) 0xFF;

        assertInstanceOf(WebpSanitizer.Invalid.class, sanitize(webp));
    }

    @Test
    void aFileThatIsNotRiffIsRefused() throws IOException {
        byte[] webp = ChatFixtures.webp(8, 8);
        webp[0] = 'X';

        assertInstanceOf(WebpSanitizer.Invalid.class, sanitize(webp));
    }

    @Test
    void anAnimatedFileIsRefusedByItsAnimationChunk() throws IOException {
        byte[] animated = ChatFixtures.riff(ChatFixtures.vp8x(0x02, 16, 16), ChatFixtures.chunk("ANIM", new byte[6]),
                ChatFixtures.chunk("ANMF", new byte[16]));

        assertInstanceOf(WebpSanitizer.Animated.class, sanitize(animated));
    }

    @Test
    void anAnimatedFileIsRefusedByItsFlagAloneToo() throws IOException {
        assertInstanceOf(WebpSanitizer.Animated.class, sanitize(ChatFixtures.riff(ChatFixtures.vp8x(0x02, 16, 16), ChatFixtures.vp8l(16, 16))));
    }

    @Test
    void aCanvasOverThePixelCapIsRefusedFromTheHeader() throws IOException {
        WebpSanitizer.TooManyPixels result = assertInstanceOf(WebpSanitizer.TooManyPixels.class, sanitize(ChatFixtures.webp(10_000, 10_000)));

        assertEquals(100_000_000L, result.pixels());
    }

    @Test
    void theFirstChunkMustBeAnImageHeader() throws IOException {
        assertInstanceOf(WebpSanitizer.Invalid.class, sanitize(ChatFixtures.riff(ChatFixtures.chunk("JUNK", new byte[4]), ChatFixtures.vp8l(8, 8))));
    }

    @Test
    void aContainerWithoutImageDataIsRefused() throws IOException {
        assertInstanceOf(WebpSanitizer.Invalid.class, sanitize(ChatFixtures.riff(ChatFixtures.vp8x(0, 8, 8), ChatFixtures.chunk("ICCP", new byte[4]))));
    }
}
