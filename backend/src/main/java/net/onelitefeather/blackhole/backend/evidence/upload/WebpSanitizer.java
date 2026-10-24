package net.onelitefeather.blackhole.backend.evidence.upload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates a WebP file as a RIFF container and writes a copy without its EXIF and XMP chunks. The image data is never decoded: the JDK
 * cannot decode WebP, and linking a native decoder would add attack surface the project does not need (libwebp had a heap overflow,
 * CVE-2023-4863). What is checked instead is the structure, in pure Java and in one linear pass:
 *
 * <ul>
 *   <li>{@code RIFF}, a size that matches the file length exactly (no bytes after the container), {@code WEBP};</li>
 *   <li>every chunk is well formed and ends inside the file;</li>
 *   <li>the first chunk is {@code VP8X}, {@code VP8 } or {@code VP8L}, and an image chunk exists;</li>
 *   <li>an animated file (an {@code ANIM} or {@code ANMF} chunk, or the animation flag of {@code VP8X}) is refused;</li>
 *   <li>the canvas size, read from the header of the chunk, stays within the pixel cap.</li>
 * </ul>
 *
 * The {@code EXIF} and {@code XMP } chunks are dropped and their flags in {@code VP8X} are cleared. A file that parses but does not
 * decode is shown as a broken image by the browser, which is harmless.
 */
public final class WebpSanitizer {

    /** The outcome: the sanitised copy or the reason the file was refused. */
    public sealed interface Result {
    }

    /** The stripped copy was written to the output; {@code width} and {@code height} are the canvas in pixels. */
    public record Sanitized(Path output, int width, int height) implements Result {
    }

    /** The canvas has more pixels than allowed. */
    public record TooManyPixels(long pixels) implements Result {
    }

    /** The file is animated. */
    public record Animated() implements Result {
    }

    /** The file is not a well formed WebP container (including bytes after it). */
    public record Invalid(String reason) implements Result {
    }

    private static final int FLAG_ANIMATION = 0x02;
    private static final int FLAG_XMP = 0x04;
    private static final int FLAG_EXIF = 0x08;
    private static final int HEADER = 12;

    /**
     * @param input     the spooled WebP
     * @param output    where the stripped copy is written (only on {@link Sanitized})
     * @param maxPixels the pixel cap checked against the canvas
     */
    public static Result sanitize(Path input, Path output, long maxPixels) throws IOException {
        byte[] data = Files.readAllBytes(input);
        if (data.length < HEADER + 8) {
            return new Invalid("the file is too short to be a WebP");
        }
        if (!tag(data, 0, "RIFF") || !tag(data, 8, "WEBP")) {
            return new Invalid("the file is not a RIFF/WEBP container");
        }
        long riffSize = le32(data, 4);
        if (riffSize + 8 != data.length) {
            return new Invalid("the RIFF size does not match the file length");
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream(data.length);
        int position = HEADER;
        boolean first = true;
        boolean image = false;
        int width = 0;
        int height = 0;
        while (position < data.length) {
            if (data.length - position < 8) {
                return new Invalid("a chunk header is cut off");
            }
            String type = new String(data, position, 4, StandardCharsets.ISO_8859_1);
            long size = le32(data, position + 4);
            int start = position + 8;
            if (size > data.length - start) {
                return new Invalid("a chunk ends behind the end of the file");
            }
            int end = start + (int) size;
            int padded = end + (int) (size % 2);
            if (padded > data.length) {
                // The padding byte after the last odd chunk may be missing, as long as the RIFF size matches the file.
                padded = data.length;
            }
            if (first && !type.equals("VP8X") && !type.equals("VP8 ") && !type.equals("VP8L")) {
                return new Invalid("the first chunk is not an image header");
            }
            switch (type) {
                case "ANIM", "ANMF" -> {
                    return new Animated();
                }
                case "EXIF", "XMP " -> {
                    // dropped
                }
                case "VP8X" -> {
                    if (!first || size < 10) {
                        return new Invalid("the VP8X chunk is misplaced or too short");
                    }
                    int flags = data[start] & 0xFF;
                    if ((flags & FLAG_ANIMATION) != 0) {
                        return new Animated();
                    }
                    width = 1 + (int) le24(data, start + 4);
                    height = 1 + (int) le24(data, start + 7);
                    byte[] chunk = new byte[padded - position];
                    System.arraycopy(data, position, chunk, 0, chunk.length);
                    chunk[8] = (byte) (flags & ~(FLAG_EXIF | FLAG_XMP));
                    body.writeBytes(chunk);
                }
                case "VP8 " -> {
                    if (size < 10 || data[start + 3] != (byte) 0x9D || data[start + 4] != 0x01 || data[start + 5] != 0x2A) {
                        return new Invalid("the VP8 frame header is invalid");
                    }
                    if (first) {
                        width = (data[start + 6] & 0xFF) | (data[start + 7] & 0x3F) << 8;
                        height = (data[start + 8] & 0xFF) | (data[start + 9] & 0x3F) << 8;
                    }
                    image = true;
                    body.write(data, position, padded - position);
                }
                case "VP8L" -> {
                    if (size < 5 || data[start] != 0x2F) {
                        return new Invalid("the VP8L signature is invalid");
                    }
                    if (first) {
                        int b1 = data[start + 1] & 0xFF;
                        int b2 = data[start + 2] & 0xFF;
                        int b3 = data[start + 3] & 0xFF;
                        int b4 = data[start + 4] & 0xFF;
                        width = 1 + (b1 | (b2 & 0x3F) << 8);
                        height = 1 + ((b2 >> 6) | b3 << 2 | (b4 & 0x0F) << 10);
                    }
                    image = true;
                    body.write(data, position, padded - position);
                }
                default -> body.write(data, position, padded - position);
            }
            first = false;
            position = padded;
        }
        if (!image) {
            return new Invalid("the file has no image data");
        }
        if (width <= 0 || height <= 0) {
            return new Invalid("the canvas has no size");
        }
        long pixels = (long) width * height;
        if (pixels > maxPixels) {
            return new TooManyPixels(pixels);
        }

        byte[] stripped = body.toByteArray();
        byte[] result = new byte[HEADER + stripped.length];
        System.arraycopy("RIFF".getBytes(StandardCharsets.ISO_8859_1), 0, result, 0, 4);
        putLe32(result, 4, 4L + stripped.length);
        System.arraycopy("WEBP".getBytes(StandardCharsets.ISO_8859_1), 0, result, 8, 4);
        System.arraycopy(stripped, 0, result, HEADER, stripped.length);
        Files.write(output, result);
        return new Sanitized(output, width, height);
    }

    private static boolean tag(byte[] data, int offset, String expected) {
        for (int i = 0; i < 4; i++) {
            if (data[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long le32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16 | (data[offset + 3] & 0xFFL) << 24;
    }

    private static long le24(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16;
    }

    private static void putLe32(byte[] target, int offset, long value) {
        for (int i = 0; i < 4; i++) {
            target[offset + i] = (byte) (value >> (8 * i));
        }
    }

    private WebpSanitizer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
