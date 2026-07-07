package net.onelitefeather.blackhole.backend.events;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/**
 * Versioned envelope published to the {@code blackhole.events} topic exchange. The routing key
 * is always equal to {@link #eventType()} (e.g. {@code punishment.created}).
 *
 * <p>Payloads must only ever carry hashed owner/IDs, never raw player UUIDs — the broker is a
 * second at-rest copy of the data and needs the same pseudonymization discipline as the
 * database.</p>
 *
 * @param eventId        a unique identifier for this event instance
 * @param eventType      the routing key / event type, e.g. {@code punishment.created}
 * @param occurredAt     epoch milliseconds when the event was raised
 * @param payloadVersion the schema version of {@link #payload()}, bumped on breaking changes
 * @param payload        the event-specific data
 */
@Serdeable
public record DomainEvent(
        String eventId,
        String eventType,
        long occurredAt,
        int payloadVersion,
        Map<String, Object> payload
) {
}
