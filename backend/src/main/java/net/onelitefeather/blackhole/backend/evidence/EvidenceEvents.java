package net.onelitefeather.blackhole.backend.evidence;

import java.util.HashMap;
import java.util.Map;

/**
 * Payloads of the evidence domain events. They name the evidence by id and hashed owner and say what
 * kind it is and which system sent it - never the payload, reference, file name or any other content.
 */
public final class EvidenceEvents {

    public static final String ATTACHED = "evidence.attached";
    public static final String DELETED = "evidence.deleted";

    /** Payload of {@code evidence.attached}. */
    public static Map<String, Object> attached(EvidenceItemEntity item) {
        return base(item);
    }

    /**
     * Payload of {@code evidence.deleted}.
     *
     * @param reason why the item was deleted
     */
    public static Map<String, Object> deleted(EvidenceItemEntity item, EvidenceDeletionReason reason) {
        Map<String, Object> payload = base(item);
        payload.put("reason", reason.name());
        return payload;
    }

    private static Map<String, Object> base(EvidenceItemEntity item) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("evidenceId", item.getIdentifier().toString());
        payload.put("punishmentId", item.getPunishmentIdentifier());
        payload.put("owner", item.getOwner());
        payload.put("kind", item.getKind().name());
        payload.put("sourceSystem", item.getSourceSystem());
        return payload;
    }

    private EvidenceEvents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
