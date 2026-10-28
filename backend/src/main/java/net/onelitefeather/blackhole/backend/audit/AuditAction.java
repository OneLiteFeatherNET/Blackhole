package net.onelitefeather.blackhole.backend.audit;

/** The fixed vocabulary of audited staff actions. The wire name is what the table and the event carry. */
public enum AuditAction {
    KICK_CREATE("kick.create"),
    KICK_MASS("kick.mass"),
    PUNISHMENT_REVOKE("punishment.revoke"),
    STAFF_LINK_UNLINK("staff-link.unlink");

    private final String wireName;

    AuditAction(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return this.wireName;
    }
}
