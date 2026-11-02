package net.onelitefeather.blackhole.backend.casetimeline.dto;

import io.micronaut.serde.annotation.Serdeable;

/** What happened to one case of a bulk edit. */
@Serdeable
public enum CaseBulkOutcome {

    /** The case changed, and its event and audit entry were written. */
    UPDATED,

    /** The case already had the requested values; nothing was written. */
    UNCHANGED,

    /** The case changed since the caller read its version; nothing was written for it. */
    CONFLICT,

    /** No punishment has this identifier. */
    NOT_FOUND,

    /** The case could not be changed (a storage error, or the tag maximum); nothing was written for it. */
    FAILED
}
