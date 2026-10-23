package net.onelitefeather.blackhole.backend.casetimeline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payloads of the {@code case.*} domain events. They name the case event, the punishment and the
 * hashed owner and carry the change without content: never a note's text. The routing key of a
 * case-own change is {@code case.} plus the timeline type with its dot turned into an underscore.
 */
public final class CaseEvents {

    private CaseEvents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The routing key for a timeline event type, such as {@code note.added} to {@code case.note_added}. */
    public static String routingKey(String timelineType) {
        return timelineType.startsWith("case.") ? timelineType : "case." + timelineType.replace('.', '_');
    }

    /**
     * The payload of a {@code case.*} event.
     *
     * @param change the non-content change of the payload (from/to, tag lists, note id, reason code)
     */
    public static Map<String, Object> payload(
            UUID caseEventId, String punishmentIdentifier, String owner, long seq, CaseActor actor, long occurredAt, Map<String, Object> change
    ) {
        Map<String, Object> payload = new HashMap<>(change);
        payload.remove("text");
        payload.put("caseEventId", caseEventId.toString());
        payload.put("punishmentIdentifier", punishmentIdentifier);
        payload.put("owner", owner);
        payload.put("seq", seq);
        payload.put("actorTrust", actor.trust().name());
        if (actor.actorOid() != null) {
            payload.put("actorOid", actor.actorOid().toString());
        }
        payload.put("occurredAt", occurredAt);
        return payload;
    }
}
