package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.serde.annotation.Serdeable;

/** Why an admin redacted a note: a code, never free text (the reason is kept in the timeline). */
@Serdeable
public enum NoteRedactionReason {
    /** The note holds personal data that must not be kept. */
    PERSONAL_DATA,
    /** A data subject asked for erasure. */
    ERASURE_REQUEST,
    /** The note is wrong or abusive. */
    INAPPROPRIATE,
    OTHER
}
