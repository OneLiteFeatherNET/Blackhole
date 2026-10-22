package net.onelitefeather.blackhole.backend.evidence.storage;

import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemBlobStoreTest {

    @TempDir
    Path directory;

    private FilesystemBlobStore store() {
        return new FilesystemBlobStore(this.directory);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void put(BlobStore store, String key, String content) throws IOException {
        store.put(key, new ByteArrayInputStream(bytes(content)), content.length(), "text/plain");
    }

    private static String read(BlobStore.BlobContent content) throws IOException {
        try (var stream = content.stream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aStoredObjectIsReadBackWhole() throws IOException {
        FilesystemBlobStore store = store();
        put(store, "2030/01/a", "0123456789");

        assertEquals("0123456789", read(store.open("2030/01/a", 0, 9)));
    }

    @Test
    void aRangeReturnsExactlyThoseBytes() throws IOException {
        FilesystemBlobStore store = store();
        put(store, "2030/01/a", "0123456789");

        BlobStore.BlobContent content = store.open("2030/01/a", 2, 5);

        assertEquals(4, content.length(), "range length");
        assertEquals("2345", read(content));
    }

    @Test
    void aRangeBeyondTheEndIsClampedToTheObject() throws IOException {
        FilesystemBlobStore store = store();
        put(store, "2030/01/a", "0123456789");

        assertEquals("789", read(store.open("2030/01/a", 7, 1000)));
    }

    @Test
    void aRangeStartingPastTheEndIsRejected() throws IOException {
        FilesystemBlobStore store = store();
        put(store, "2030/01/a", "0123456789");

        assertThrows(IllegalArgumentException.class, () -> store.open("2030/01/a", 10, 12));
    }

    @Test
    void openingAMissingObjectSaysSo() {
        assertThrows(BlobStore.NoSuchBlobException.class, () -> store().open("2030/01/missing", 0, 1));
    }

    @Test
    void deletingIsIdempotent() throws IOException {
        FilesystemBlobStore store = store();
        put(store, "2030/01/a", "x");

        assertTrue(store.delete("2030/01/a"), "the first delete removes the object");
        assertFalse(store.delete("2030/01/a"), "deleting it again is not an error");
    }

    @Test
    void aKeyThatEscapesTheDirectoryIsRejected() {
        FilesystemBlobStore store = store();

        assertThrows(IllegalArgumentException.class, () -> put(store, "../../etc/passwd", "x"));
        assertThrows(IllegalArgumentException.class, () -> put(store, "2030/../../outside", "x"));
        assertThrows(IllegalArgumentException.class, () -> store.open("../secret", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> store.delete("/etc/passwd"));
    }

    @Test
    void nothingIsWrittenOutsideTheDirectoryByATraversalAttempt() throws IOException {
        Path outside = this.directory.resolveSibling("outside-" + System.nanoTime());
        FilesystemBlobStore store = store();

        assertThrows(IllegalArgumentException.class, () -> put(store, "../" + outside.getFileName(), "x"));

        assertFalse(Files.exists(outside), "the traversal target must not have been created");
    }

    @Test
    void aShortWriteLeavesNoObjectAndNoTempFile() throws IOException {
        FilesystemBlobStore store = store();

        assertThrows(IOException.class, () -> store.put("2030/01/short", new ByteArrayInputStream(bytes("abc")), 10, "text/plain"));

        assertFalse(Files.exists(this.directory.resolve("2030/01/short")), "no partial object");
        try (var files = Files.list(this.directory.resolve("2030/01"))) {
            assertEquals(0, files.count(), "the temp file is cleaned up");
        }
    }

    @Test
    void overwritingReplacesTheObject() throws IOException {
        FilesystemBlobStore store = store();
        put(store, "2030/01/a", "old");
        put(store, "2030/01/a", "newer");

        assertEquals("newer", read(store.open("2030/01/a", 0, 4)));
    }

    @Test
    void listingReturnsOnlyObjectsOlderThanTheCutoff() throws IOException {
        FilesystemBlobStore store = store();
        put(store, "2030/01/old", "x");
        put(store, "2030/01/new", "y");
        Files.setLastModifiedTime(this.directory.resolve("2030/01/old"), java.nio.file.attribute.FileTime.from(TestClock.START.minusSeconds(3600)));
        Files.setLastModifiedTime(this.directory.resolve("2030/01/new"), java.nio.file.attribute.FileTime.from(TestClock.START.plusSeconds(3600)));

        List<BlobStore.StoredBlob> older = store.listOlderThan(TestClock.START);

        assertEquals(List.of("2030/01/old"), older.stream().map(BlobStore.StoredBlob::key).toList());
        assertTrue(older.get(0).lastModified().isBefore(Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void generatedKeysHaveTheExpectedShapeAndNeverContainAClientName() {
        String key = BlobKeys.generate(new TestClock());

        assertTrue(BlobKeys.isGenerated(key), key);
        assertTrue(key.startsWith("2030/01/"), "prefixed with year and month of the clock: " + key);
        assertFalse(BlobKeys.isGenerated("../../etc/passwd"));
    }
}
