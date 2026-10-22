package net.onelitefeather.blackhole.backend.evidence.storage;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores evidence files in an S3-compatible bucket through the AWS SDK for Java v2 (Apache-2.0).
 * The bucket is created on first use if it does not exist. Reads use ranged GETs, so video
 * scrubbing never downloads the whole object. Evidence is never exposed through presigned URLs:
 * the backend streams it itself, which keeps the store private and every download audited.
 */
@Singleton
@Requires(property = "blackhole.evidence.files.storage", value = "s3", defaultValue = "s3")
public class S3BlobStore implements BlobStore {

    private final S3Client client;
    private final String bucket;
    private final AtomicBoolean bucketReady = new AtomicBoolean();

    public S3BlobStore(S3Client client, EvidenceConfig config) {
        this.client = client;
        this.bucket = config.getFiles().getS3().getBucket();
    }

    private void ensureBucket() throws IOException {
        if (this.bucketReady.get()) {
            return;
        }
        try {
            try {
                this.client.headBucket(HeadBucketRequest.builder().bucket(this.bucket).build());
            } catch (NoSuchBucketException missing) {
                this.client.createBucket(CreateBucketRequest.builder().bucket(this.bucket).build());
            }
            this.bucketReady.set(true);
        } catch (RuntimeException e) {
            throw new IOException("cannot reach bucket " + this.bucket + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) throws IOException {
        ensureBucket();
        try {
            this.client.putObject(PutObjectRequest.builder().bucket(this.bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromInputStream(content, length));
        } catch (RuntimeException e) {
            throw new IOException("cannot store " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public BlobContent open(String key, long start, long endInclusive) throws IOException {
        if (start < 0 || endInclusive < start) {
            throw new IllegalArgumentException("invalid range " + start + "-" + endInclusive);
        }
        try {
            ResponseInputStream<GetObjectResponse> stream = this.client.getObject(GetObjectRequest.builder()
                    .bucket(this.bucket).key(key).range("bytes=" + start + "-" + endInclusive).build());
            return new BlobContent(stream, stream.response().contentLength());
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            throw new NoSuchBlobException(key);
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String key) throws IOException {
        try {
            boolean existed = exists(key);
            this.client.deleteObject(DeleteObjectRequest.builder().bucket(this.bucket).key(key).build());
            return existed;
        } catch (RuntimeException e) {
            throw new IOException("cannot delete " + key + ": " + e.getMessage(), e);
        }
    }

    private boolean exists(String key) {
        try {
            this.client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<StoredBlob> listOlderThan(Instant cutoff) throws IOException {
        ensureBucket();
        List<StoredBlob> blobs = new ArrayList<>();
        try {
            this.client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(this.bucket).build()).contents().forEach(object -> {
                if (isBefore(object, cutoff)) {
                    blobs.add(new StoredBlob(object.key(), object.lastModified()));
                }
            });
        } catch (RuntimeException e) {
            throw new IOException("cannot list bucket " + this.bucket + ": " + e.getMessage(), e);
        }
        return blobs;
    }

    private static boolean isBefore(S3Object object, Instant cutoff) {
        return object.lastModified() != null && object.lastModified().isBefore(cutoff);
    }
}
