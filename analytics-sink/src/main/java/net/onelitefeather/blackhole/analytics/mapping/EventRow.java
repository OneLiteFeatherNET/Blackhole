package net.onelitefeather.blackhole.analytics.mapping;

import java.time.Instant;
import java.util.Map;

/**
 * One row of an analytics event table, before it is written to Iceberg.
 *
 * @param table          target table, e.g. {@code punishment_events}
 * @param eventId        the envelope id, the dedupe key
 * @param eventType      the event type
 * @param occurredAt     when the event was raised (UTC)
 * @param payloadVersion the payload version the row was mapped from
 * @param owner          the owner hash, or {@code null} when the event has none
 * @param typed          values of the family's typed columns; a key is absent when the payload had no such key
 * @param extra          JSON object holding every other payload key except the owner
 */
public record EventRow(
        String table,
        String eventId,
        String eventType,
        Instant occurredAt,
        int payloadVersion,
        String owner,
        Map<String, Object> typed,
        String extra
) {
}
