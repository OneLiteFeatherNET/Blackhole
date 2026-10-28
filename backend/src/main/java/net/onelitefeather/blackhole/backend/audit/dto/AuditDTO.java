package net.onelitefeather.blackhole.backend.audit.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of {@code /admin/audit}: the staff audit log, read only.
 */
@Schema(description = "Staff audit log entries")
@Serdeable
public sealed interface AuditDTO {

    /**
     * One cursor page, newest first.
     *
     * @param items      the entries of this page
     * @param nextCursor the opaque cursor of the next page, or {@code null} on the last page
     */
    @Schema(name = "AuditPage")
    @Serdeable
    record Page(List<Item> items, @Nullable String nextCursor) implements AuditDTO {
    }

    /**
     * One audit entry. It names its target by a case short id or a hashed owner, never by a Ban ID, IP or raw UUID.
     */
    @Schema(name = "AuditItem")
    @Serdeable
    record Item(
            UUID auditId,
            long occurredAt,
            UUID actorOid,
            @Nullable String actorName,
            String surface,
            String action,
            String targetKind,
            @Nullable String targetRef,
            @Nullable String panelPath,
            UUID recordedByOid
    ) {
    }

    /** An invalid request, answered with its status. */
    @Schema(name = "AuditError")
    @Serdeable
    record Error(String message, ErrorKind kind) implements AuditDTO {
    }
}
