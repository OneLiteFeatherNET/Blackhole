package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.audit.StaffAuditEntry;
import net.onelitefeather.blackhole.backend.audit.StaffAuditStore;

import java.util.ArrayList;
import java.util.List;

/** Keeps the audit rows in memory, in insertion order. A fresh instance per test fixture. */
public final class InMemoryStaffAuditStore implements StaffAuditStore {

    private final List<StaffAuditEntry> rows = new ArrayList<>();

    /** Makes the next inserts fail, as a database outage would. */
    private boolean failing;

    @Override
    public void insert(StaffAuditEntry entry) {
        if (this.failing) {
            throw new IllegalStateException("database is down");
        }
        this.rows.add(entry);
    }

    public void failInserts(boolean failing) {
        this.failing = failing;
    }

    public List<StaffAuditEntry> rows() {
        return List.copyOf(this.rows);
    }
}
