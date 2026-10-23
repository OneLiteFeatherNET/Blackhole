package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.List;

/**
 * The {@code blackhole.evidence.*} tunables. The intent of every property is documented next to
 * its default in {@code application.yml}. Invalid values fail the application at startup (the bean is
 * created eagerly, so a bad value is reported before the housekeeping schedule is built from it).
 */
@Context
@ConfigurationProperties("blackhole.evidence")
public class EvidenceConfig {

    /** Which {@link net.onelitefeather.blackhole.backend.evidence.storage.BlobStore} holds uploaded files. */
    public enum Storage {
        S3,
        LOCAL
    }

    private int maxPayloadBytes = 32_768;
    private int maxChatLines = 50;
    private Duration pendingTtl = Duration.ofDays(1);
    private Duration reportPendingTtl = Duration.ofDays(30);
    private Duration auditRetention;

    private Housekeeping housekeeping = new Housekeeping();
    private Files files = new Files();
    private Scan scan = new Scan();

    @PostConstruct
    void validate() {
        require(this.maxPayloadBytes > 0, "blackhole.evidence.max-payload-bytes must be positive");
        require(this.maxChatLines > 0, "blackhole.evidence.max-chat-lines must be positive");
        requirePositive(this.pendingTtl, "blackhole.evidence.pending-ttl");
        requirePositive(this.reportPendingTtl, "blackhole.evidence.report-pending-ttl");
        if (this.auditRetention != null) {
            requirePositive(this.auditRetention, "blackhole.evidence.audit-retention");
        }
        requirePositive(this.housekeeping.interval, "blackhole.evidence.housekeeping.interval");
        require(this.files.maxImageBytes > 0, "blackhole.evidence.files.max-image-bytes must be positive");
        require(this.files.maxVideoBytes > 0, "blackhole.evidence.files.max-video-bytes must be positive");
        require(this.files.maxImagePixels > 0, "blackhole.evidence.files.max-image-pixels must be positive");
        require(this.files.allowedTypes != null && !this.files.allowedTypes.isEmpty(),
                "blackhole.evidence.files.allowed-types must not be empty");
        require(this.scan.clamd.port > 0 && this.scan.clamd.port < 65_536, "blackhole.evidence.scan.clamd.port must be a valid port");
        requirePositive(this.scan.clamd.timeout, "blackhole.evidence.scan.clamd.timeout");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requirePositive(Duration duration, String property) {
        require(duration != null && !duration.isNegative() && !duration.isZero(), property + " must be a positive ISO-8601 duration");
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getMaxChatLines() {
        return maxChatLines;
    }

    public void setMaxChatLines(int maxChatLines) {
        this.maxChatLines = maxChatLines;
    }

    public Duration getPendingTtl() {
        return pendingTtl;
    }

    public void setPendingTtl(Duration pendingTtl) {
        this.pendingTtl = pendingTtl;
    }

    /** How long pending evidence attached to a report (correlation id prefix {@code report:}) waits for a punishment. */
    public Duration getReportPendingTtl() {
        return reportPendingTtl;
    }

    public void setReportPendingTtl(Duration reportPendingTtl) {
        this.reportPendingTtl = reportPendingTtl;
    }

    /** How long audit rows are kept, or {@code null} to keep them indefinitely (the default). */
    @Nullable
    public Duration getAuditRetention() {
        return auditRetention;
    }

    public void setAuditRetention(@Nullable Duration auditRetention) {
        this.auditRetention = auditRetention;
    }

    public Housekeeping getHousekeeping() {
        return housekeeping;
    }

    public void setHousekeeping(Housekeeping housekeeping) {
        this.housekeeping = housekeeping;
    }

    public Files getFiles() {
        return files;
    }

    public void setFiles(Files files) {
        this.files = files;
    }

    public Scan getScan() {
        return scan;
    }

    public void setScan(Scan scan) {
        this.scan = scan;
    }

    @ConfigurationProperties("housekeeping")
    public static class Housekeeping {

        private Duration interval = Duration.ofHours(1);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }

    @ConfigurationProperties("files")
    public static class Files {

        private Storage storage = Storage.S3;
        private long maxImageBytes = 10L * 1024 * 1024;
        private long maxVideoBytes = 100L * 1024 * 1024;
        private long maxImagePixels = 50_000_000L;
        private List<String> allowedTypes = List.of("image/png", "image/jpeg", "video/mp4", "video/webm");
        private Local local = new Local();
        private S3 s3 = new S3();

        public Storage getStorage() {
            return storage;
        }

        public void setStorage(Storage storage) {
            this.storage = storage;
        }

        public long getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(long maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
        }

        public long getMaxVideoBytes() {
            return maxVideoBytes;
        }

        public void setMaxVideoBytes(long maxVideoBytes) {
            this.maxVideoBytes = maxVideoBytes;
        }

        public long getMaxImagePixels() {
            return maxImagePixels;
        }

        public void setMaxImagePixels(long maxImagePixels) {
            this.maxImagePixels = maxImagePixels;
        }

        public List<String> getAllowedTypes() {
            return allowedTypes;
        }

        public void setAllowedTypes(List<String> allowedTypes) {
            this.allowedTypes = allowedTypes;
        }

        public Local getLocal() {
            return local;
        }

        public void setLocal(Local local) {
            this.local = local;
        }

        public S3 getS3() {
            return s3;
        }

        public void setS3(S3 s3) {
            this.s3 = s3;
        }

        @ConfigurationProperties("local")
        public static class Local {

            private String path = "evidence-files";

            public String getPath() {
                return path;
            }

            public void setPath(String path) {
                this.path = path;
            }
        }

        @ConfigurationProperties("s3")
        public static class S3 {

            private String endpoint = "";
            private String bucket = "blackhole-evidence";
            private String region = "us-east-1";
            private String accessKey = "";
            private String secretKey = "";
            private boolean pathStyle = true;

            public String getEndpoint() {
                return endpoint;
            }

            public void setEndpoint(String endpoint) {
                this.endpoint = endpoint;
            }

            public String getBucket() {
                return bucket;
            }

            public void setBucket(String bucket) {
                this.bucket = bucket;
            }

            public String getRegion() {
                return region;
            }

            public void setRegion(String region) {
                this.region = region;
            }

            public String getAccessKey() {
                return accessKey;
            }

            public void setAccessKey(String accessKey) {
                this.accessKey = accessKey;
            }

            public String getSecretKey() {
                return secretKey;
            }

            public void setSecretKey(String secretKey) {
                this.secretKey = secretKey;
            }

            public boolean isPathStyle() {
                return pathStyle;
            }

            public void setPathStyle(boolean pathStyle) {
                this.pathStyle = pathStyle;
            }
        }
    }

    @ConfigurationProperties("scan")
    public static class Scan {

        private boolean enabled = true;
        private Clamd clamd = new Clamd();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Clamd getClamd() {
            return clamd;
        }

        public void setClamd(Clamd clamd) {
            this.clamd = clamd;
        }

        @ConfigurationProperties("clamd")
        public static class Clamd {

            private String host = "localhost";
            private int port = 3310;
            private Duration timeout = Duration.ofSeconds(30);

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public Duration getTimeout() {
                return timeout;
            }

            public void setTimeout(Duration timeout) {
                this.timeout = timeout;
            }
        }
    }
}
