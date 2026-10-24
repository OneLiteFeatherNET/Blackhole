package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Who supplied an evidence item. Stored as the ordinal in a {@code smallint} column (default {@code STAFF}, so every
 * row from before the chat is staff evidence), so new values are only ever appended.
 */
@Serdeable
public enum EvidenceProvenance {

    /** Attached by staff or by the proxy on their behalf: the evidence the team stands behind. */
    STAFF,

    /** Uploaded by the appealing player in the appeal chat: shown as player-supplied and unverified, not part of the team count. */
    PLAYER
}
