package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.serde.annotation.Serdeable;

/** Why evidence was deleted; published with {@code evidence.deleted}. */
@Serdeable
public enum EvidenceDeletionReason {

    /** An admin deleted a single item. */
    MANUAL,

    /** An admin deleted all evidence of an owner because of a data-subject erasure request. */
    ERASURE,

    /** The punishment record the evidence belonged to was deleted. */
    CASCADE,

    /** Pending evidence was not claimed by a punishment within the pending TTL. */
    UNCLAIMED_EXPIRED
}
