package net.onelitefeather.blackhole.backend.evidence.service;

import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceDeletionReason;
import net.onelitefeather.blackhole.backend.security.Attribution;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one door the appeal protocol uses into the evidence package: it stores backend-generated content
 * as evidence of kind {@code TRANSCRIPT}, opens it for staff (audited) and deletes it. It is deliberately
 * narrow, like {@link PunishmentEvidenceGateway}: the protocol service depends on nothing else of the
 * package.
 *
 * <p>Generated content is stored as it is: no upload allow-list, magic-byte sniffing, malware scan,
 * re-encoding or UUID/IP guards, because those would reject or rewrite the record the protocol is
 * meant to equal. Its size is bounded by {@code blackhole.protocol.max-bytes} instead.</p>
 */
public interface GeneratedEvidenceGateway {

    /** Which of the two files of a transcript item. */
    enum Variant {
        TEXT,
        JSON
    }

    /**
     * A stored transcript item.
     *
     * @param textBytes size of the text file
     * @param jsonBytes size of the JSON file
     */
    record Stored(UUID evidenceId, long textBytes, long jsonBytes) {
    }

    /** One file of a transcript item, read for a staff member. */
    record Opened(byte[] bytes, String contentType, String fileName) {
    }

    /** A transcript could not be stored. */
    class StoreException extends Exception {

        private final boolean tooLarge;

        public StoreException(String message, boolean tooLarge, Throwable cause) {
            super(message, cause);
            this.tooLarge = tooLarge;
        }

        /** Whether a file exceeded {@code blackhole.protocol.max-bytes} (as opposed to a failing store). */
        public boolean tooLarge() {
            return this.tooLarge;
        }
    }

    /**
     * Stores the pair of files as one new {@code TRANSCRIPT} item of the punishment, with source system
     * {@code blackhole}, no creator and status {@code VALID}. Both files are written before the row, so a
     * failure leaves at most an orphan file the housekeeping sweep removes.
     *
     * @param baseName a file name without extension, free of personal data
     * @param metadata ids and counts describing the item (appeal id, version); never content
     * @throws StoreException when a file is larger than the bound, the punishment is unknown or the store fails
     */
    Stored store(String punishmentIdentifier, String baseName, byte[] text, byte[] json, Map<String, Object> metadata) throws StoreException;

    /**
     * Reads one file of a transcript item and writes the audit entry.
     *
     * @param action {@link EvidenceAuditAction#VIEWED} or {@link EvidenceAuditAction#DOWNLOADED}
     * @return the file, or empty when there is no such transcript item
     * @throws IOException when the store cannot be read
     */
    Optional<Opened> open(UUID evidenceId, Variant variant, EvidenceAuditAction action, Attribution by) throws IOException;

    /**
     * Deletes a transcript item and both files; the audit entry stays, without content.
     *
     * @return whether an item existed
     * @throws IOException when a file cannot be deleted (the item then stays, for a retry)
     */
    boolean delete(UUID evidenceId, EvidenceDeletionReason reason) throws IOException;
}
