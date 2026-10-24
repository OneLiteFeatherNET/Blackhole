package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

/**
 * One case event about to be appended.
 *
 * @param punishmentIdentifier the punishment (and so the case) it belongs to
 * @param payload              the typed body; its {@link CaseEventPayload#type()} is the event type
 * @param occurredAt           epoch millis: the injected clock for a live event, the source row's time for a backfilled or reconstructed one
 * @param origin               how the event came to be
 * @param actor                who caused it
 * @param subjectId            what it is about (appeal id, evidence id), if anything
 * @param dedupeKey            the deterministic key that makes recording it twice a no-op, or {@code null} for an event that may repeat
 * @param visibility           {@value #STAFF} (default) or {@value #PLAYER}; set by the server, never by a request
 */
public record CaseEventDraft(
        String punishmentIdentifier,
        CaseEventPayload payload,
        long occurredAt,
        CaseEventOrigin origin,
        CaseActor actor,
        @Nullable String subjectId,
        @Nullable String dedupeKey,
        String visibility
) {

    public static final String STAFF = "STAFF";
    public static final String PLAYER = "PLAYER";

    /** A staff-only event, which is what every lifecycle and note event is. */
    public CaseEventDraft(
            String punishmentIdentifier, CaseEventPayload payload, long occurredAt, CaseEventOrigin origin, CaseActor actor,
            @Nullable String subjectId, @Nullable String dedupeKey
    ) {
        this(punishmentIdentifier, payload, occurredAt, origin, actor, subjectId, dedupeKey, STAFF);
    }

    public String type() {
        return this.payload.type();
    }
}
