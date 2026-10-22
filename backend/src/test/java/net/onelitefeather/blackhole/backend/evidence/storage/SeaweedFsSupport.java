package net.onelitefeather.blackhole.backend.evidence.storage;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

/**
 * A throwaway SeaweedFS (Apache-2.0) with its S3 API, the S3-compatible store the evidence tests run
 * against. The credentials test / test-secret are handed to the S3 gateway through the AWS_* environment variables. The image tag is
 * pinned; bump it deliberately.
 */
public final class SeaweedFsSupport {

    public static final String IMAGE = "chrislusf/seaweedfs:3.99";
    private static final int S3_PORT = 8333;

    public static GenericContainer<?> container() {
        return new GenericContainer<>(IMAGE)
                .withEnv("AWS_ACCESS_KEY_ID", "test")
                .withEnv("AWS_SECRET_ACCESS_KEY", "test-secret")
                .withCommand("server", "-s3", "-dir=/data")
                .withExposedPorts(S3_PORT)
                .waitingFor(Wait.forHttp("/").forPort(S3_PORT).forStatusCodeMatching(status -> status < 500)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /** Application properties that point the S3 blob store at the running container. */
    public static Map<String, Object> properties(GenericContainer<?> seaweed, String bucket) {
        return Map.of(
                "blackhole.evidence.files.storage", "s3",
                "blackhole.evidence.files.s3.endpoint", "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(S3_PORT),
                "blackhole.evidence.files.s3.bucket", bucket,
                "blackhole.evidence.files.s3.access-key", "test",
                "blackhole.evidence.files.s3.secret-key", "test-secret",
                "blackhole.evidence.files.s3.path-style", true
        );
    }

    private SeaweedFsSupport() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
