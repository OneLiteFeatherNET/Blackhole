package net.onelitefeather.blackhole.backend.evidence.upload;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.scan.EvidenceScanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Everything an uploaded file goes through before it is stored, working on the spooled temporary
 * file: malware scan, magic-byte type detection against the allow-list, per-type size caps and, for
 * images, the pixel cap and the metadata-stripping re-encode.
 *
 * <p>The scan runs first on the file exactly as uploaded, so a flagged file is always refused with
 * 422 whatever it claims to be, and an unavailable scanner always refuses with 503 (fail closed).
 * The client's MIME type and file name play no part in any decision.
 */
@Singleton
public class UploadPipeline {

    private final EvidenceConfig config;
    private final EvidenceScanner scanner;

    public UploadPipeline(EvidenceConfig config, EvidenceScanner scanner) {
        this.config = config;
        this.scanner = scanner;
    }

    /**
     * An upload that passed every check.
     *
     * @param file        the bytes to store: the re-encoded image, or the video as uploaded
     * @param contentType the type detected from the magic bytes
     * @param size        the size of {@code file}
     * @param sha256      the SHA-256 of {@code file}
     * @param displayName the sanitised file name
     */
    public record Accepted(Path file, String contentType, long size, String sha256, String displayName) {
    }

    /** The outcome: {@link #accepted()} or {@link #error()}, never both. */
    public record Outcome(Accepted accepted, EvidenceDTO.Error error) {

        static Outcome of(Accepted accepted) {
            return new Outcome(accepted, null);
        }

        static Outcome rejected(EvidenceErrorKind kind, String message) {
            return new Outcome(null, new EvidenceDTO.Error(message, kind));
        }
    }

    /** The most bytes any allowed type may have, so the spooler can stop an oversized body early. */
    public long maxUploadBytes() {
        long max = 0;
        for (String type : this.config.getFiles().getAllowedTypes()) {
            max = Math.max(max, type.startsWith("image/") ? this.config.getFiles().getMaxImageBytes() : this.config.getFiles().getMaxVideoBytes());
        }
        return max;
    }

    /**
     * Checks and, for images, rewrites the spooled upload. The caller owns {@code spooled} and
     * deletes it; the {@link Accepted#file()} of an image is a second temporary file in the same
     * directory that the caller also deletes.
     *
     * @param spooled    the upload as received
     * @param clientName the client's file name, used only for the sanitised display name
     */
    public Outcome process(Path spooled, String clientName) throws IOException {
        EvidenceScanner.ScanResult verdict = this.scanner.scan(spooled);
        if (verdict instanceof EvidenceScanner.Infected infected) {
            return Outcome.rejected(EvidenceErrorKind.UNPROCESSABLE, "the file was flagged by the virus scanner (" + infected.signature() + ")");
        }
        if (verdict instanceof EvidenceScanner.Unavailable unavailable) {
            return Outcome.rejected(EvidenceErrorKind.UNAVAILABLE, "uploads are unavailable while the virus scanner cannot be reached");
        }

        Optional<FileSniffer.Type> detected = FileSniffer.detect(head(spooled));
        if (detected.isEmpty()) {
            return Outcome.rejected(EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE, "the file is not a supported image or video");
        }
        FileSniffer.Type type = detected.get();
        if (!this.config.getFiles().getAllowedTypes().contains(type.contentType())) {
            return Outcome.rejected(EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE, type.contentType() + " uploads are not allowed");
        }
        long inputSize = Files.size(spooled);
        long limit = type.isImage() ? this.config.getFiles().getMaxImageBytes() : this.config.getFiles().getMaxVideoBytes();
        if (inputSize > limit) {
            return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE,
                    type.contentType() + " files may be at most " + limit + " bytes");
        }

        Path result = spooled;
        if (type.isImage()) {
            Path reencoded = Files.createTempFile(spooled.getParent(), "evidence-image-", ".tmp");
            ImageSanitizer.Result sanitized = ImageSanitizer.sanitize(
                    spooled, reencoded, type == FileSniffer.Type.PNG ? "png" : "jpeg", this.config.getFiles().getMaxImagePixels());
            if (sanitized instanceof ImageSanitizer.TooManyPixels tooMany) {
                Files.deleteIfExists(reencoded);
                return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE,
                        "the image has " + tooMany.pixels() + " pixels, the limit is " + this.config.getFiles().getMaxImagePixels());
            }
            if (sanitized instanceof ImageSanitizer.Undecodable) {
                Files.deleteIfExists(reencoded);
                return Outcome.rejected(EvidenceErrorKind.UNPROCESSABLE, "the file is not a valid " + type.contentType() + " image");
            }
            result = reencoded;
            if (Files.size(result) > limit) {
                Files.deleteIfExists(result);
                return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE, type.contentType() + " files may be at most " + limit + " bytes");
            }
        }
        return Outcome.of(new Accepted(result, type.contentType(), Files.size(result), sha256(result),
                FileNames.sanitize(clientName, type.extension())));
    }

    private static byte[] head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(FileSniffer.HEAD_BYTES);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
