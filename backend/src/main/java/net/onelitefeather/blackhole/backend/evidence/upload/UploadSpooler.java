package net.onelitefeather.blackhole.backend.evidence.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies an upload stream into a bounded temporary file. The copy stops as soon as the limit is
 * exceeded, so an oversized upload never fills the disk and the body is never held in memory:
 * only one buffer is alive at a time. A known length is what S3 needs for a PUT, and what hashing,
 * sniffing and virus scanning need to read the bytes more than once.
 */
public final class UploadSpooler {

    private static final int BUFFER = 64 * 1024;

    /**
     * @param file     the temporary file holding the whole upload, {@code null} if it exceeded the limit
     * @param size     the bytes copied (at most one buffer past the limit when {@code tooLarge})
     * @param tooLarge whether the upload exceeded the limit and was discarded
     */
    public record Spooled(Path file, long size, boolean tooLarge) {
    }

    /**
     * Spools {@code in} to a new temporary file.
     *
     * @param directory the directory for the temporary file
     * @param maxBytes  the most bytes accepted
     */
    public static Spooled spool(InputStream in, Path directory, long maxBytes) throws IOException {
        Path file = Files.createTempFile(directory, "evidence-upload-", ".tmp");
        boolean keep = false;
        try (var out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    return new Spooled(null, total, true);
                }
                out.write(buffer, 0, read);
            }
            keep = true;
            return new Spooled(file, total, false);
        } finally {
            if (!keep) {
                Files.deleteIfExists(file);
            }
        }
    }

    private UploadSpooler() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
