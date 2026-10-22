package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.serde.annotation.Serdeable;

/** The reason an admin has to give when deleting evidence; recorded in the audit trail. */
@Serdeable
public enum EvidenceDeleteReason {
    ERASURE_REQUEST,
    WRONG_EVIDENCE,
    OTHER
}
