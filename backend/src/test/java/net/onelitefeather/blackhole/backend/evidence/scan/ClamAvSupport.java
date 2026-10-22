package net.onelitefeather.blackhole.backend.evidence.scan;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

/**
 * A throwaway ClamAV {@code clamd} (image pinned). The first start downloads the signature database,
 * needs network access and about 1 GB of RAM, so tests using it are opt-in: set the environment
 * variable {@value #OPT_IN} to {@code true}. The limits are raised the way the compose file does, so
 * a 100 MiB video can be streamed.
 */
public final class ClamAvSupport {

    public static final String IMAGE = "clamav/clamav:1.5.4";
    public static final String OPT_IN = "BLACKHOLE_IT_CLAMAV";
    private static final int PORT = 3310;

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv(OPT_IN));
    }

    public static GenericContainer<?> container() {
        return new GenericContainer<>(IMAGE)
                .withEnv("CLAMAV_NO_FRESHCLAMD", "false")
                .withEnv("CLAMD_CONF_StreamMaxLength", "110M")
                .withEnv("CLAMD_CONF_MaxFileSize", "110M")
                .withEnv("CLAMD_CONF_MaxScanSize", "220M")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forLogMessage(".*socket found, clamd started.*\\n", 1).withStartupTimeout(Duration.ofMinutes(8)));
    }

    /** Application properties that point the scanner at the running container. */
    public static Map<String, Object> properties(GenericContainer<?> clamav) {
        return Map.of(
                "blackhole.evidence.scan.enabled", true,
                "blackhole.evidence.scan.clamd.host", clamav.getHost(),
                "blackhole.evidence.scan.clamd.port", clamav.getMappedPort(PORT)
        );
    }

    private ClamAvSupport() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
