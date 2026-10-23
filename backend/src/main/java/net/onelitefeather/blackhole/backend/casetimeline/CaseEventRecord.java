package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * A stored case event.
 *
 * @param seq     the total order and paging cursor; ties in {@code occurredAt} break by it
 * @param body    the stored JSON object; empty after a redaction
 */
public record CaseEventRecord(
        UUID identifier,
        long seq,
        String punishmentIdentifier,
        String type,
        int payloadVersion,
        long occurredAt,
        CaseEventOrigin origin,
        ActorTrust actorTrust,
        @Nullable UUID actorOid,
        @Nullable UUID recordedByOid,
        @Nullable UUID actorSource,
        @Nullable String subjectId,
        String visibility,
        Map<String, Object> body,
        @Nullable Long redactedAt,
        @Nullable String dedupeKey
) {

    /** The timeline order: {@code occurredAt}, then {@code seq}. */
    public static final Comparator<CaseEventRecord> TIMELINE_ORDER =
            Comparator.comparingLong(CaseEventRecord::occurredAt).thenComparingLong(CaseEventRecord::seq);

    /** The typed payload, or an {@link CaseEventPayload.Unknown} for a type or version this release does not know. */
    public CaseEventPayload payload() {
        return CaseEventPayloads.read(this.type, this.payloadVersion, this.body);
    }
}
