package net.onelitefeather.blackhole.backend.evidence.upload;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Byte-exact WebP, PDF and text files for the chat profile tests: built in memory, no files, no network. */
public final class ChatFixtures {

    public static final String GPS_MARKER = "GPS-LAT-52.5200N-LON-13.4050E";

    private ChatFixtures() {
    }

    /** A chunk: four-character type, little endian size, data and the padding byte of an odd size. */
    public static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(le32(data.length));
        out.writeBytes(data);
        if (data.length % 2 == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    /** The header of a lossless image of the given size; the rest of the data is filler (the sanitiser never decodes it). */
    public static byte[] vp8l(int width, int height) {
        long bits = (width - 1) | ((long) (height - 1) << 14);
        byte[] data = new byte[12];
        data[0] = 0x2F;
        for (int i = 0; i < 4; i++) {
            data[1 + i] = (byte) (bits >> (8 * i));
        }
        return chunk("VP8L", data);
    }

    /** The extended header: flags, then width-1 and height-1 as 24-bit little endian numbers. */
    public static byte[] vp8x(int flags, int width, int height) {
        byte[] data = new byte[10];
        data[0] = (byte) flags;
        for (int i = 0; i < 3; i++) {
            data[4 + i] = (byte) ((width - 1) >> (8 * i));
            data[7 + i] = (byte) ((height - 1) >> (8 * i));
        }
        return chunk("VP8X", data);
    }

    public static byte[] riff(byte[]... chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            body.writeBytes(chunk);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(le32(4 + body.size()));
        out.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /** A plain lossless WebP of the given size. */
    public static byte[] webp(int width, int height) {
        return riff(vp8l(width, height));
    }

    /** An extended WebP with EXIF (carrying GPS data) and XMP chunks and the flags that announce them. */
    public static byte[] webpWithMetadata(int width, int height) {
        return riff(vp8x(0x08 | 0x04 | 0x10, width, height), vp8l(width, height),
                chunk("EXIF", ("Exif\0\0" + GPS_MARKER).getBytes(StandardCharsets.ISO_8859_1)),
                chunk("XMP ", ("<x:xmpmeta>" + GPS_MARKER + "</x:xmpmeta>").getBytes(StandardCharsets.ISO_8859_1)));
    }

    public static byte[] withTrailing(byte[] file, int extra) {
        byte[] longer = new byte[file.length + extra];
        System.arraycopy(file, 0, longer, 0, file.length);
        return longer;
    }

    /** A small PDF with the given extra dictionary entries in its catalog. */
    public static byte[] pdf(String catalogEntries) {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog " + catalogEntries + " >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] le32(long value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }
}
