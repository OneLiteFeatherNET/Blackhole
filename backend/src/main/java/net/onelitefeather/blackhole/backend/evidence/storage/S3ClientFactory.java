package net.onelitefeather.blackhole.backend.evidence.storage;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Builds the S3 client from {@code blackhole.evidence.files.s3.*}. An empty endpoint means AWS S3
 * itself; any other endpoint (SeaweedFS, Ceph, Cloudflare R2, Backblaze B2, ...) is addressed
 * path-style by default. Static credentials are used when both keys are set, otherwise the AWS
 * default credential chain.
 */
@Factory
public class S3ClientFactory {

    @Bean(preDestroy = "close")
    @Singleton
    @Requires(property = "blackhole.evidence.files.storage", value = "s3", defaultValue = "s3")
    S3Client s3Client(EvidenceConfig config) {
        EvidenceConfig.Files.S3 s3 = config.getFiles().getS3();
        AwsCredentialsProvider credentials = s3.getAccessKey().isBlank() || s3.getSecretKey().isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s3.isPathStyle()).build())
                // S3-compatible services do not all implement the newer default checksum trailers.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (!s3.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3.getEndpoint()));
        }
        return builder.build();
    }
}
