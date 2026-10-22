package net.onelitefeather.blackhole.backend.evidence.scan;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.nio.file.Path;

/**
 * Finds everything clean. Only active when {@code blackhole.evidence.scan.enabled=false}, i.e. when
 * the operator explicitly opted out of scanning (for example because uploads are scanned elsewhere).
 */
@Singleton
@Requires(property = "blackhole.evidence.scan.enabled", value = "false")
public class NoopScanner implements EvidenceScanner {

    @Override
    public ScanResult scan(Path file) {
        return new Clean();
    }
}
