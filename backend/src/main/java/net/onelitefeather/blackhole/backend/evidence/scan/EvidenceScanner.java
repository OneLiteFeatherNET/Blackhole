package net.onelitefeather.blackhole.backend.evidence.scan;

import java.nio.file.Path;

/**
 * Scans an uploaded file for malware before it is stored. The contract is <b>fail closed</b>: a
 * scanner that cannot give a verdict answers {@link Unavailable}, never {@link Clean}, and the
 * upload is then rejected. The only scanner that always answers clean is {@link NoopScanner}, which
 * exists for tests and for deployments that explicitly disable scanning.
 */
public interface EvidenceScanner {

    /** Scans the file. Never throws for a scanner problem; answers {@link Unavailable} instead. */
    ScanResult scan(Path file);

    /** What the scanner found. */
    sealed interface ScanResult {
    }

    /** The scanner looked at the file and found nothing. */
    record Clean() implements ScanResult {
    }

    /** The scanner flagged the file; {@code signature} names the detection. */
    record Infected(String signature) implements ScanResult {
    }

    /** The scanner could not give a verdict (down, timed out, answered with an error). */
    record Unavailable(String reason) implements ScanResult {
    }
}
