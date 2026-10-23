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
 */
public record CaseEventDraft(
        String punishmentIdentifier,
        CaseEventPayload payload,
        long occurredAt,
        CaseEventOrigin origin,
        CaseActor actor,
        @Nullable String subjectId,
        @Nullable String dedupeKey
) {

    public String type() {
        return this.payload.type();
    }
}
