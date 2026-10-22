package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What an evidence item is. Stored as the ordinal in a {@code smallint} column, so new kinds are
 * only ever appended.
 */
@Serdeable
public enum EvidenceKind {

    /** A structured record of the check an anticheat flagged. */
    ANTICHEAT,

    /** A link to a replay recording held by another system. */
    REPLAY,

    /** An excerpt of chat lines. */
    CHAT_LOG,

    /** Any other link, for example a message in a staff chat. */
    LINK,

    /** A screenshot or video uploaded to and stored by Blackhole. */
    FILE
}
