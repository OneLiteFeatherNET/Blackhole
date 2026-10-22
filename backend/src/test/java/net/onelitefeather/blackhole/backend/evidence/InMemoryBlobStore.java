package net.onelitefeather.blackhole.backend.evidence;

import net.onelitefeather.blackhole.backend.evidence.storage.BlobStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link BlobStore} held in a map, so service tests need no disk or network. */
public final class InMemoryBlobStore implements BlobStore {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, Instant> modified = new LinkedHashMap<>();
    private boolean failing;

    /** Makes every following call throw, as an unreachable store would. */
    public void fail() {
        this.failing = true;
    }

    public Set<String> keys() {
        return this.objects.keySet();
    }

    public byte[] bytes(String key) {
        return this.objects.get(key);
    }

    /** Puts bytes in the store as if an earlier upload had stored them. */
    public void given(String key, byte[] content) {
        this.objects.put(key, content);
    }

    /** Puts bytes in the store as if an earlier upload had stored them at the given time. */
    public void given(String key, byte[] content, Instant lastModified) {
        this.objects.put(key, content);
        this.modified.put(key, lastModified);
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) throws IOException {
        check();
        this.objects.put(key, content.readAllBytes());
    }

    @Override
    public BlobContent open(String key, long start, long endInclusive) throws IOException {
        check();
        byte[] all = this.objects.get(key);
        if (all == null) {
            throw new NoSuchBlobException(key);
        }
        int to = (int) Math.min(endInclusive, all.length - 1L);
        byte[] slice = Arrays.copyOfRange(all, (int) start, to + 1);
        return new BlobContent(new ByteArrayInputStream(slice), slice.length);
    }

    @Override
    public boolean delete(String key) throws IOException {
        check();
        this.modified.remove(key);
        return this.objects.remove(key) != null;
    }

    @Override
    public List<StoredBlob> listOlderThan(Instant cutoff) throws IOException {
        check();
        return this.objects.keySet().stream()
                .map(key -> new StoredBlob(key, this.modified.getOrDefault(key, Instant.EPOCH)))
                .filter(blob -> blob.lastModified().isBefore(cutoff))
                .toList();
    }

    private void check() throws IOException {
        if (this.failing) {
            throw new IOException("store unavailable");
        }
    }
}
