package net.onelitefeather.blackhole.backend.evidence.storage;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stores evidence files in a directory. Suitable for one backend replica, or several sharing a
 * volume; S3-compatible storage is the default for anything larger. Every key is resolved against
 * the base directory and must stay inside it. Files are written under a temporary name and moved
 * into place atomically, so a reader never sees a partial file.
 */
@Singleton
@Requires(property = "blackhole.evidence.files.storage", value = "local")
public class FilesystemBlobStore implements BlobStore {

    private static final String TEMP_PREFIX = ".tmp-";

    private final Path base;

    @Inject
    public FilesystemBlobStore(EvidenceConfig config) {
        this(Path.of(config.getFiles().getLocal().getPath()));
    }

    public FilesystemBlobStore(Path base) {
        this.base = base.toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("\0")) {
            throw new IllegalArgumentException("invalid blob key");
        }
        Path resolved = this.base.resolve(key).normalize();
        if (!resolved.startsWith(this.base) || resolved.equals(this.base)) {
            throw new IllegalArgumentException("blob key escapes the storage directory: " + key);
        }
        return resolved;
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(TEMP_PREFIX + UUID.randomUUID());
        try {
            long written = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            if (written != length) {
                throw new IOException("expected " + length + " bytes but received " + written);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public BlobContent open(String key, long start, long endInclusive) throws IOException {
        Path file = resolve(key);
        FileChannel channel;
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            throw new NoSuchBlobException(key);
        }
        try {
            long size = channel.size();
            if (start < 0 || start >= size || endInclusive < start) {
                throw new IllegalArgumentException("range " + start + "-" + endInclusive + " does not fit an object of " + size + " bytes");
            }
            long end = Math.min(endInclusive, size - 1);
            long length = end - start + 1;
            channel.position(start);
            InputStream stream = new java.io.FilterInputStream(Channels.newInputStream(channel)) {
                private long remaining = length;

                @Override
                public int read() throws IOException {
                    if (this.remaining <= 0) {
                        return -1;
                    }
                    int value = super.read();
                    if (value >= 0) {
                        this.remaining--;
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int count) throws IOException {
                    if (this.remaining <= 0) {
                        return -1;
                    }
                    int read = super.read(buffer, offset, (int) Math.min(count, this.remaining));
                    if (read > 0) {
                        this.remaining -= read;
                    }
                    return read;
                }
            };
            return new BlobContent(stream, length);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public boolean delete(String key) throws IOException {
        return Files.deleteIfExists(resolve(key));
    }

    @Override
    public List<StoredBlob> listOlderThan(Instant cutoff) throws IOException {
        if (!Files.isDirectory(this.base)) {
            return List.of();
        }
        List<StoredBlob> blobs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(this.base)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (modified.isBefore(cutoff)) {
                    blobs.add(new StoredBlob(this.base.relativize(file).toString().replace('\\', '/'), modified));
                }
            }
        }
        return blobs;
    }
}
