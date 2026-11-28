package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/** One row of {@code staff_audit}. Deliberately has no free text and no Ban ID, IP address or raw UUID. */
public record StaffAuditEntry(
        UUID auditId,
        long occurredAt,
        UUID actorOid,
        @Nullable String actorName,
        Surface surface,
        String action,
        String targetKind,
        @Nullable String targetRef,
        UUID recordedByOid
) {

    /** The panel path the entry links to, or {@code null} when the target has no page. */
    @Nullable
    public String panelPath() {
        return AuditTarget.panelPathOf(this.targetKind, this.targetRef);
    }
}
