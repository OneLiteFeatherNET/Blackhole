package net.onelitefeather.blackhole.backend.evidence.storage;

import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The S3 adapter against a real S3-compatible server (SeaweedFS): put, ranged get, delete, list. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class S3BlobStoreIntegrationTest {

    private static GenericContainer<?> seaweed;
    private static S3Client client;

    @BeforeAll
    static void startSeaweed() {
        seaweed = SeaweedFsSupport.container();
        seaweed.start();
        client = new S3ClientFactory().s3Client(config("blackhole-unit"));
    }

    @AfterAll
    static void stopSeaweed() {
        if (client != null) {
            client.close();
        }
        if (seaweed != null) {
            seaweed.stop();
        }
    }

    private static EvidenceConfig config(String bucket) {
        EvidenceConfig config = new EvidenceConfig();
        EvidenceConfig.Files.S3 s3 = config.getFiles().getS3();
        s3.setEndpoint("http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333));
        s3.setBucket(bucket);
        s3.setAccessKey("test");
        s3.setSecretKey("test-secret");
        return config;
    }

    /** A store on its own bucket, so each test is independent of the others. */
    private static S3BlobStore freshStore() {
        return new S3BlobStore(client, config("bucket-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private static void put(BlobStore store, String key, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
    }

    private static String read(BlobStore.BlobContent content) throws IOException {
        try (var stream = content.stream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void anObjectIsStoredAndReadBack() throws IOException {
        S3BlobStore store = freshStore();
        put(store, "2030/01/a", "0123456789");

        assertEquals("0123456789", read(store.open("2030/01/a", 0, 9)));
    }

    @Test
    void aRangedGetReturnsExactlyTheRequestedBytes() throws IOException {
        S3BlobStore store = freshStore();
        put(store, "2030/01/a", "0123456789");

        BlobStore.BlobContent content = store.open("2030/01/a", 3, 6);

        assertEquals(4, content.length(), "range length");
        assertEquals("3456", read(content));
    }

    @Test
    void openingAMissingObjectSaysSo() {
        S3BlobStore store = freshStore();

        assertThrows(BlobStore.NoSuchBlobException.class, () -> store.open("2030/01/missing", 0, 1));
    }

    @Test
    void deleteRemovesTheObjectAndIsIdempotent() throws IOException {
        S3BlobStore store = freshStore();
        put(store, "2030/01/a", "x");

        assertTrue(store.delete("2030/01/a"), "the first delete removes the object");
        assertFalse(store.delete("2030/01/a"), "deleting a missing object is not an error");
        assertThrows(BlobStore.NoSuchBlobException.class, () -> store.open("2030/01/a", 0, 0));
    }

    @Test
    void listingReturnsObjectsOlderThanTheCutoffOnly() throws IOException {
        S3BlobStore store = freshStore();
        put(store, "2030/01/a", "x");

        assertEquals(1, store.listOlderThan(Instant.now().plusSeconds(60)).size(), "a fresh object is older than a future cutoff");
        assertEquals(0, store.listOlderThan(Instant.now().minusSeconds(3600)).size(), "and not older than a past one");
    }
}
