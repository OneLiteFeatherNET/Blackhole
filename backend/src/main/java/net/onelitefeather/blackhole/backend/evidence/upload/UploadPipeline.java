package net.onelitefeather.blackhole.backend.evidence.upload;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.ChatAttachmentConfig;
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
import java.util.Set;

/**
 * Everything an uploaded file goes through before it is stored, working on the spooled temporary
 * file: malware scan, magic-byte type detection against the allow-list, per-type size caps and the
 * type-specific step (images are re-encoded or stripped, PDFs and text are checked).
 *
 * <p>The scan runs first on the file exactly as uploaded, so a flagged file is always refused with
 * 422 whatever it claims to be, and an unavailable scanner always refuses with 503 (fail closed).
 * The client's MIME type and file name play no part in any decision. An {@link UploadProfile} picks
 * the rules: {@code EVIDENCE} is staff evidence (PNG, JPEG, MP4, WebM), {@code CHAT} the files of the
 * appeal conversation (PNG, JPEG, WebP, PDF, UTF-8 text). A profile never changes the other's allowed types.
 *
 * <p>The spool directory is the quarantine: until the scan said clean, the bytes are in no store, no
 * URL and no response.
 */
@Singleton
public class UploadPipeline {

    /** The types the staff evidence profile can ever accept, whatever {@code allowed-types} says. */
    private static final Set<FileSniffer.Type> EVIDENCE_TYPES = Set.of(
            FileSniffer.Type.PNG, FileSniffer.Type.JPEG, FileSniffer.Type.MP4, FileSniffer.Type.WEBM);

    private final EvidenceConfig config;
    private final ChatAttachmentConfig chat;
    private final EvidenceScanner scanner;

    @Inject
    public UploadPipeline(EvidenceConfig config, ChatAttachmentConfig chat, EvidenceScanner scanner) {
        this.config = config;
        this.chat = chat;
        this.scanner = scanner;
    }

    /** With the default chat limits, for the staff evidence profile alone. */
    public UploadPipeline(EvidenceConfig config, EvidenceScanner scanner) {
        this(config, new ChatAttachmentConfig(), scanner);
    }

    /**
     * An upload that passed every check.
     *
     * @param file        the bytes to store: the re-encoded or stripped image, or the file as uploaded
     * @param contentType the type detected from the magic bytes
     * @param size        the size of {@code file}
     * @param sha256      the SHA-256 of {@code file}
     * @param displayName the sanitised file name
     * @param width       the pixel width of an image, otherwise {@code null}
     * @param height      the pixel height of an image, otherwise {@code null}
     */
    public record Accepted(Path file, String contentType, long size, String sha256, String displayName,
                           @Nullable Integer width, @Nullable Integer height) {

        public Accepted(Path file, String contentType, long size, String sha256, String displayName) {
            this(file, contentType, size, sha256, displayName, null, null);
        }
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

    /** The most bytes any allowed staff evidence type may have, so the spooler can stop an oversized body early. */
    public long maxUploadBytes() {
        return maxUploadBytes(UploadProfile.EVIDENCE);
    }

    /** The most bytes the profile accepts of any type. */
    public long maxUploadBytes(UploadProfile profile) {
        if (profile == UploadProfile.CHAT) {
            return this.chat.getMaxBytes();
        }
        long max = 0;
        for (String type : this.config.getFiles().getAllowedTypes()) {
            max = Math.max(max, type.startsWith("image/") ? this.config.getFiles().getMaxImageBytes() : this.config.getFiles().getMaxVideoBytes());
        }
        return max;
    }

    /**
     * Checks and, for images, rewrites the spooled staff evidence upload.
     *
     * @see #process(Path, String, UploadProfile)
     */
    public Outcome process(Path spooled, String clientName) throws IOException {
        return process(spooled, clientName, UploadProfile.EVIDENCE);
    }

    /**
     * Checks and, where the type calls for it, rewrites the spooled upload. The caller owns {@code spooled} and
     * deletes it; the {@link Accepted#file()} of an image is a second temporary file in the same
     * directory that the caller also deletes.
     *
     * @param spooled    the upload as received
     * @param clientName the client's file name, used only for the sanitised display name
     * @param profile    which rules apply
     */
    public Outcome process(Path spooled, String clientName, UploadProfile profile) throws IOException {
        return profile == UploadProfile.CHAT ? processChat(spooled, clientName) : processEvidence(spooled, clientName);
    }

    private Outcome processEvidence(Path spooled, String clientName) throws IOException {
        EvidenceScanner.ScanResult verdict = this.scanner.scan(spooled);
        if (verdict instanceof EvidenceScanner.Infected infected) {
            return Outcome.rejected(EvidenceErrorKind.UNPROCESSABLE, "the file was flagged by the virus scanner (" + infected.signature() + ")");
        }
        if (verdict instanceof EvidenceScanner.Unavailable unavailable) {
            return Outcome.rejected(EvidenceErrorKind.UNAVAILABLE, "uploads are unavailable while the virus scanner cannot be reached");
        }

        Optional<FileSniffer.Type> detected = FileSniffer.detect(head(spooled)).filter(EVIDENCE_TYPES::contains);
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
        Integer width = null;
        Integer height = null;
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
            if (sanitized instanceof ImageSanitizer.Sanitized done) {
                width = done.width();
                height = done.height();
            }
            result = reencoded;
            if (Files.size(result) > limit) {
                Files.deleteIfExists(result);
                return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE, type.contentType() + " files may be at most " + limit + " bytes");
            }
        }
        return Outcome.of(new Accepted(result, type.contentType(), Files.size(result), sha256(result),
                FileNames.sanitize(clientName, type.extension()), width, height));
    }

    private Outcome processChat(Path spooled, String clientName) throws IOException {
        EvidenceScanner.ScanResult verdict = this.scanner.scan(spooled);
        if (verdict instanceof EvidenceScanner.Infected) {
            // The signature name is not shown: it would tell an uploader which scanner and rule caught the file.
            return Outcome.rejected(EvidenceErrorKind.FILE_FLAGGED, "the file was flagged by the virus scanner");
        }
        if (verdict instanceof EvidenceScanner.Unavailable) {
            return Outcome.rejected(EvidenceErrorKind.UNAVAILABLE, "uploads are unavailable while the virus scanner cannot be reached");
        }

        long inputSize = Files.size(spooled);
        if (inputSize == 0) {
            return Outcome.rejected(EvidenceErrorKind.UNPROCESSABLE, "the file is empty");
        }
        Optional<FileSniffer.Type> detected = FileSniffer.detect(head(spooled));
        FileSniffer.Type type;
        if (detected.isPresent()) {
            type = detected.get();
        } else if (TextGuard.isPlainText(spooled)) {
            type = FileSniffer.Type.TEXT;
        } else {
            return Outcome.rejected(EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE, "this file type is not allowed");
        }
        if (EVIDENCE_ONLY.contains(type) || !this.chat.getAllowedTypes().contains(type.contentType())) {
            return Outcome.rejected(EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE, "this file type is not allowed");
        }
        if (inputSize > this.chat.getMaxBytes()) {
            return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE, "files may be at most " + this.chat.getMaxBytes() + " bytes");
        }

        Path result = spooled;
        Integer width = null;
        Integer height = null;
        switch (type) {
            case PNG, JPEG -> {
                Path reencoded = Files.createTempFile(spooled.getParent(), "evidence-image-", ".tmp");
                ImageSanitizer.Result sanitized = ImageSanitizer.sanitize(
                        spooled, reencoded, type == FileSniffer.Type.PNG ? "png" : "jpeg", this.config.getFiles().getMaxImagePixels());
                if (sanitized instanceof ImageSanitizer.TooManyPixels) {
                    Files.deleteIfExists(reencoded);
                    return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE, "the image has too many pixels");
                }
                if (!(sanitized instanceof ImageSanitizer.Sanitized done)) {
                    Files.deleteIfExists(reencoded);
                    return Outcome.rejected(EvidenceErrorKind.UNPROCESSABLE, "the file is not a valid image");
                }
                width = done.width();
                height = done.height();
                result = reencoded;
            }
            case WEBP -> {
                Path stripped = Files.createTempFile(spooled.getParent(), "evidence-image-", ".tmp");
                WebpSanitizer.Result sanitized = WebpSanitizer.sanitize(spooled, stripped, this.config.getFiles().getMaxImagePixels());
                switch (sanitized) {
                    case WebpSanitizer.TooManyPixels ignored -> {
                        Files.deleteIfExists(stripped);
                        return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE, "the image has too many pixels");
                    }
                    case WebpSanitizer.Animated ignored -> {
                        Files.deleteIfExists(stripped);
                        return Outcome.rejected(EvidenceErrorKind.UNSUPPORTED_MEDIA_TYPE, "animated images are not allowed");
                    }
                    case WebpSanitizer.Invalid ignored -> {
                        Files.deleteIfExists(stripped);
                        return Outcome.rejected(EvidenceErrorKind.UNPROCESSABLE, "the file is not a valid WebP image");
                    }
                    case WebpSanitizer.Sanitized done -> {
                        width = done.width();
                        height = done.height();
                        result = stripped;
                    }
                }
            }
            case PDF -> {
                if (PdfGuard.check(spooled) != PdfGuard.Verdict.OK) {
                    return Outcome.rejected(EvidenceErrorKind.FILE_ACTIVE_CONTENT, "the PDF contains active content or is encrypted");
                }
            }
            default -> {
                // TEXT was decided by TextGuard above.
            }
        }
        long size = Files.size(result);
        if (size > this.chat.getMaxBytes()) {
            if (!result.equals(spooled)) {
                Files.deleteIfExists(result);
            }
            return Outcome.rejected(EvidenceErrorKind.PAYLOAD_TOO_LARGE, "files may be at most " + this.chat.getMaxBytes() + " bytes");
        }
        return Outcome.of(new Accepted(result, type.contentType(), size, sha256(result),
                FileNames.sanitizeChat(clientName, type.extension()), width, height));
    }

    /** The video types, which the chat profile never accepts. */
    private static final Set<FileSniffer.Type> EVIDENCE_ONLY = Set.of(FileSniffer.Type.MP4, FileSniffer.Type.WEBM);

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
