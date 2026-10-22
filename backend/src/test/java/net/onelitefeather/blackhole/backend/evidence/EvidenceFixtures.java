package net.onelitefeather.blackhole.backend.evidence;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Small, valid (or deliberately crafted) files for the upload tests, generated in memory. */
public final class EvidenceFixtures {

    /** The EICAR antivirus test string: harmless, but every scanner flags it. */
    public static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    /** A marker that only exists in the injected EXIF segment, to prove it was stripped. */
    public static final String GPS_MARKER = "GPS-LAT-52.5200N-LON-13.4050E";

    public static byte[] png(int width, int height) throws IOException {
        return encode(width, height, "png", BufferedImage.TYPE_INT_ARGB);
    }

    public static byte[] jpeg(int width, int height) throws IOException {
        return encode(width, height, "jpeg", BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] encode(int width, int height, String format, int type) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0xFF000000 | (x * 7 << 16) | (y * 5 << 8) | 0x40);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** A JPEG carrying an APP1 "Exif" segment with {@link #GPS_MARKER} inside, right after the SOI marker. */
    public static byte[] jpegWithExif() throws IOException {
        byte[] jpeg = jpeg(24, 16);
        byte[] exifBody = ("Exif\0\0" + GPS_MARKER).getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buffer = ByteBuffer.allocate(jpeg.length + exifBody.length + 4);
        buffer.put(jpeg, 0, 2);
        buffer.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (exifBody.length + 2)).put(exifBody);
        buffer.put(jpeg, 2, jpeg.length - 2);
        return buffer.array();
    }

    /** A PNG header (signature + IHDR with a valid CRC) that claims the given size and has no pixel data. */
    public static byte[] pngClaiming(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] data = ihdr.array();
        CRC32 crc = new CRC32();
        crc.update("IHDR".getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        ByteBuffer out = ByteBuffer.allocate(8 + 4 + 4 + 13 + 4);
        out.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        out.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).put(data).putInt((int) crc.getValue());
        return out.array();
    }

    /** Enough of an MP4 for the sniffer: an ftyp box followed by filler up to {@code size} bytes. */
    public static byte[] mp4(int size) {
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putInt(24).put("ftypisom".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("isomiso2".getBytes(StandardCharsets.US_ASCII));
        return out.array();
    }

    /** Enough of a WebM for the sniffer: the EBML magic, a DocType of "webm", then filler. */
    public static byte[] webm(int size) {
        ByteBuffer out = ByteBuffer.allocate(size);
        out.put(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x84, 0x42, (byte) 0x82, (byte) 0x84}).put("webm".getBytes(StandardCharsets.US_ASCII));
        return out.array();
    }

    /** A Windows executable header, the classic "image" that is not one. */
    public static byte[] executable() {
        return ("MZ" + "\u0090\0\u0003\0\0\0\u0004\0\0\0 This program cannot be run in DOS mode.").getBytes(StandardCharsets.ISO_8859_1);
    }

    private EvidenceFixtures() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
