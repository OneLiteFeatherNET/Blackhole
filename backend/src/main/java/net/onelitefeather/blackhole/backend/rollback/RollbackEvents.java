package net.onelitefeather.blackhole.backend.rollback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payloads of {@code staff.rollback.started} and {@code staff.rollback.completed}. Counts and the job identifier
 * only: never an owner hash, a player name or the reason text.
 */
public final class RollbackEvents {

    public static final String STARTED = "staff.rollback.started";
    public static final String COMPLETED = "staff.rollback.completed";

    private RollbackEvents() {
    }

    /** A job was started; {@code frozen} is how many punishments it will revoke. */
    public static Map<String, Object> started(UUID jobId, UUID requestedByOid, long frozen) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId.toString());
        payload.put("requestedBy", requestedByOid.toString());
        payload.put("frozen", frozen);
        return payload;
    }

    /** A job has no pending item left; {@code counts} are the items per state. */
    public static Map<String, Object> completed(UUID jobId, UUID requestedByOid, Map<String, Long> counts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId.toString());
        payload.put("requestedBy", requestedByOid.toString());
        payload.put("revoked", counts.getOrDefault("REVOKED", 0L));
        payload.put("skipped", counts.getOrDefault("SKIPPED", 0L));
        payload.put("failed", counts.getOrDefault("FAILED", 0L));
        return payload;
    }
}
