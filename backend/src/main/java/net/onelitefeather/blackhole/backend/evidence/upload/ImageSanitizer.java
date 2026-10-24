package net.onelitefeather.blackhole.backend.evidence.upload;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Strips metadata from PNG and JPEG uploads by decoding and re-encoding them: a freshly written
 * image carries no EXIF, GPS, XMP or text chunks. The pixel count is read from the header and
 * checked before anything is decoded, so a decompression bomb is rejected cheaply.
 */
public final class ImageSanitizer {

    private static final float JPEG_QUALITY = 0.9f;

    /** The outcome: either the re-encoded file or the reason it was refused. */
    public sealed interface Result {
    }

    /** The image was re-encoded to {@code output}; {@code width} and {@code height} are its size in pixels. */
    public record Sanitized(Path output, int width, int height) implements Result {

        public Sanitized(Path output) {
            this(output, 0, 0);
        }
    }

    /** The image has more pixels than allowed; nothing was decoded. */
    public record TooManyPixels(long pixels) implements Result {
    }

    /** The file claims to be an image but cannot be decoded. */
    public record Undecodable(String reason) implements Result {
    }

    /**
     * Re-encodes the image in {@code input} to {@code output}.
     *
     * @param format    {@code png} or {@code jpeg}
     * @param maxPixels the pixel cap checked against the header before decoding
     */
    public static Result sanitize(Path input, Path output, String format, long maxPixels) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(input.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (in == null || !readers.hasNext()) {
                return new Undecodable("no decoder for " + format);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > maxPixels) {
                    return new TooManyPixels(pixels);
                }
                BufferedImage image = reader.read(0);
                write(image, output, format);
                return new Sanitized(output, image.getWidth(), image.getHeight());
            } catch (IOException | RuntimeException e) {
                return new Undecodable(String.valueOf(e.getMessage()));
            } finally {
                reader.dispose();
            }
        }
    }

    private static void write(BufferedImage image, Path output, String format) throws IOException {
        BufferedImage toWrite = image;
        if (format.equals("jpeg") && image.getColorModel().hasAlpha()) {
            toWrite = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = toWrite.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IOException("no encoder for " + format);
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format.equals("jpeg")) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            // Null metadata: nothing from the original (EXIF, GPS, camera data) is carried over.
            writer.write(null, new IIOImage(toWrite, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private ImageSanitizer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
