package net.onelitefeather.blackhole.backend.evidence.service;

import java.util.UUID;

/**
 * Whether the stored protocol behind a {@code TRANSCRIPT} evidence item may still be read. A redaction
 * makes every version of an appeal's protocol unreadable at once, before its files are deleted, and the
 * evidence routes must honour that: the answer lives with the protocol index, not with the evidence row.
 */
public interface TranscriptAccess {

    /**
     * @return {@code false} when the item is a protocol version that was marked for purging or was purged
     */
    boolean readable(UUID evidenceId);
}
