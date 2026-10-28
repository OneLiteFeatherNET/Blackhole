package net.onelitefeather.blackhole.backend.audit;

/** Append-only storage of audit entries. One statement per call, so none needs a transaction. */
public interface StaffAuditStore {

    /**
     * @throws RuntimeException when the row could not be stored
     */
    void insert(StaffAuditEntry entry);
}
