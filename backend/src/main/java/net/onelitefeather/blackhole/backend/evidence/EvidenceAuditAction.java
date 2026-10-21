package net.onelitefeather.blackhole.backend.evidence;

/** What happened to an evidence item, as recorded in the audit trail. Stored as the ordinal. */
public enum EvidenceAuditAction {
    ATTACHED,
    VIEWED,
    VIEWED_INACTIVE,
    DOWNLOADED,
    DELETED
}
