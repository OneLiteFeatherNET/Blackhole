package net.onelitefeather.blackhole.backend.evidence.upload;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Determines what an uploaded file really is from its first bytes. The client's MIME type and file
 * name are never consulted. Only the formats Blackhole can allow are recognised; anything else
 * is "unknown" and therefore rejected (the chat profile then tests for text, which has no magic bytes).
 */
public final class FileSniffer {

    /** How many leading bytes {@link #detect} needs: a PDF header may sit anywhere in the first kilobyte. */
    public static final int HEAD_BYTES = 1024;

    /** How far into the file the container signatures of video are looked for. */
    private static final int CONTAINER_BYTES = 64;

    /** A recognised file type. */
    public enum Type {
        PNG("image/png", "png", true),
        JPEG("image/jpeg", "jpg", true),
        WEBP("image/webp", "webp", true),
        PDF("application/pdf", "pdf", false),
        /** Never returned by {@link #detect}: text has no magic bytes, the chat profile tests for it last. */
        TEXT("text/plain", "txt", false),
        MP4("video/mp4", "mp4", false),
        WEBM("video/webm", "webm", false);

        private final String contentType;
        private final String extension;
        private final boolean image;

        Type(String contentType, String extension, boolean image) {
            this.contentType = contentType;
            this.extension = extension;
            this.image = image;
        }

        public String contentType() {
            return this.contentType;
        }

        public String extension() {
            return this.extension;
        }

        public boolean isImage() {
            return this.image;
        }
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    /**
     * @param head the first bytes of the file (at most {@value #HEAD_BYTES} are used)
     * @return the detected type, empty if the bytes match none of the supported formats
     */
    public static Optional<Type> detect(byte[] head) {
        if (startsWith(head, PNG)) {
            return Optional.of(Type.PNG);
        }
        if (startsWith(head, JPEG)) {
            return Optional.of(Type.JPEG);
        }
        if (isWebp(head)) {
            return Optional.of(Type.WEBP);
        }
        // ISO base media: a box size, then "ftyp" at offset 4.
        if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return Optional.of(Type.MP4);
        }
        // Matroska and WebM share the EBML magic; the DocType string tells them apart.
        if (startsWith(head, EBML) && new String(head, 0, Math.min(head.length, CONTAINER_BYTES), StandardCharsets.ISO_8859_1).contains("webm")) {
            return Optional.of(Type.WEBM);
        }
        if (containsPdfHeader(head)) {
            return Optional.of(Type.PDF);
        }
        return Optional.empty();
    }

    private static boolean isWebp(byte[] head) {
        return head.length >= 12 && startsWith(head, RIFF) && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
    }

    private static boolean containsPdfHeader(byte[] head) {
        int last = Math.min(head.length, HEAD_BYTES) - PDF_MAGIC.length;
        for (int offset = 0; offset <= last; offset++) {
            boolean match = true;
            for (int i = 0; i < PDF_MAGIC.length && match; i++) {
                match = head[offset + i] == PDF_MAGIC[i];
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private FileSniffer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
