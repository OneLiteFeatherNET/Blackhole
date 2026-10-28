package net.onelitefeather.blackhole.backend.audit;

/**
 * Whether the staff action of one request was recorded. One per request, carried by its
 * {@link net.onelitefeather.blackhole.backend.security.Actor}: the recorder marks it when an entry could not be
 * stored, and the response helper {@link AuditHeaders} turns the mark into the {@code X-Audit} header.
 */
public final class AuditReceipt {

    private volatile boolean unrecorded;

    /** Notes that the action succeeded but its entry could not be stored. */
    public void markUnrecorded() {
        this.unrecorded = true;
    }

    public boolean unrecorded() {
        return this.unrecorded;
    }
}
