package net.onelitefeather.blackhole.analytics.mapping;

import java.util.Map;

/**
 * The envelope the backend publishes on {@code blackhole.events.<eventType>}. The sink keeps its own copy so it does
 * not depend on backend internals.
 *
 * @param eventId        unique id of this event instance; the dedupe key
 * @param eventType      e.g. {@code punishment.created}
 * @param occurredAt     epoch milliseconds when the event was raised
 * @param payloadVersion schema version of the payload
 * @param payload        the event-specific data, hashed owners and identifiers only
 */
public record EventEnvelope(String eventId, String eventType, long occurredAt, int payloadVersion, Map<String, Object> payload) {
}
