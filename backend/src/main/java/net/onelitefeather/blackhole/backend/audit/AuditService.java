package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.dto.AuditDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.util.List;
import java.util.UUID;

/** Reads the audit log, newest first, one cursor page at a time. */
@Singleton
public class AuditService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final StaffAuditStore store;

    public AuditService(StaffAuditStore store) {
        this.store = store;
    }

    /**
     * @param limit     1 to 100, default 50
     * @param cursor    the {@code nextCursor} of the previous page, or {@code null} for the first page
     * @param action    optional action filter (a wire name such as {@code kick.create})
     * @param actorOid  optional actor filter
     * @param targetRef optional target filter (case short id or owner hash)
     * @return the {@link AuditDTO.Page}, or an {@link AuditDTO.Error} for an invalid limit or cursor
     */
    public AuditDTO page(@Nullable Integer limit, @Nullable String cursor, @Nullable String action, @Nullable UUID actorOid,
                         @Nullable String targetRef) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            return new AuditDTO.Error("limit must be between 1 and " + MAX_LIMIT, ErrorKind.VALIDATION);
        }
        long before = Long.MAX_VALUE;
        if (cursor != null) {
            try {
                before = Long.parseLong(cursor);
            } catch (NumberFormatException e) {
                return new AuditDTO.Error("cursor is not one returned by this endpoint", ErrorKind.VALIDATION);
            }
        }
        List<StaffAuditStore.Row> rows = this.store.page(before, size, action, actorOid, targetRef);
        List<AuditDTO.Item> items = rows.stream().map(row -> item(row.entry())).toList();
        String next = rows.size() == size ? String.valueOf(rows.getLast().seq()) : null;
        return new AuditDTO.Page(items, next);
    }

    private static AuditDTO.Item item(StaffAuditEntry entry) {
        return new AuditDTO.Item(entry.auditId(), entry.occurredAt(), entry.actorOid(), entry.actorName(),
                entry.surface().name(), entry.action(), entry.targetKind(), entry.targetRef(), entry.panelPath(), entry.recordedByOid());
    }
}
