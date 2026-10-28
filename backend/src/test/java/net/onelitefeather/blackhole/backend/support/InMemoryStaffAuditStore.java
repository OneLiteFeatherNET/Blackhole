package net.onelitefeather.blackhole.backend.support;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.audit.StaffAuditEntry;
import net.onelitefeather.blackhole.backend.audit.StaffAuditStore;
import net.onelitefeather.blackhole.backend.audit.StaffAuditStore.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Keeps the audit rows in memory, in insertion order (the sequence is the position). A fresh instance per test. */
public final class InMemoryStaffAuditStore implements StaffAuditStore {

    private final List<Row> rows = new ArrayList<>();
    private boolean failing;

    /** Makes the next inserts fail, as a database outage would. */
    public void failInserts(boolean failing) {
        this.failing = failing;
    }

    @Override
    public synchronized void insert(StaffAuditEntry entry) {
        if (this.failing) {
            throw new IllegalStateException("database is down");
        }
        this.rows.add(new Row(this.rows.size() + 1L, entry));
    }

    @Override
    public synchronized List<Row> page(long beforeSeq, int limit, @Nullable String action, @Nullable UUID actorOid, @Nullable String targetRef) {
        return this.rows.stream()
                .filter(row -> row.seq() < beforeSeq)
                .filter(row -> action == null || action.equals(row.entry().action()))
                .filter(row -> actorOid == null || actorOid.equals(row.entry().actorOid()))
                .filter(row -> targetRef == null || targetRef.equals(row.entry().targetRef()))
                .sorted((a, b) -> Long.compare(b.seq(), a.seq()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized int deleteOccurredBefore(long cutoff, int limit) {
        List<Row> old = this.rows.stream().filter(row -> row.entry().occurredAt() < cutoff).limit(limit).toList();
        this.rows.removeAll(old);
        return old.size();
    }

    @Override
    public synchronized void clearActorName(UUID actorOid) {
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            if (Objects.equals(row.entry().actorOid(), actorOid)) {
                StaffAuditEntry e = row.entry();
                this.rows.set(i, new Row(row.seq(), new StaffAuditEntry(e.auditId(), e.occurredAt(), e.actorOid(), null,
                        e.surface(), e.action(), e.targetKind(), e.targetRef(), e.recordedByOid())));
            }
        }
    }

    /** The entries in insertion order. */
    public synchronized List<StaffAuditEntry> rows() {
        return this.rows.stream().map(Row::entry).toList();
    }

}
