package net.onelitefeather.blackhole.analytics.mapping;

import java.util.Map;

/** Builds envelopes in the shape the backend publishes them. Fixed values only: no clock, no randomness. */
final class EventFixtures {

    /** 2026-09-21T16:53:20Z, epoch milliseconds as the backend writes them. */
    static final long OCCURRED_AT_MILLIS = 1_790_000_000_000L;

    /** A hashed owner, as the backend stores it (SHA-512 hex, no raw UUID). */
    static final String OWNER_HASH = "f".repeat(128);

    static EventEnvelope envelope(String eventType, Map<String, Object> payload) {
        return new EventEnvelope("event-1", eventType, OCCURRED_AT_MILLIS, 1, payload);
    }

    static EventEnvelope envelope(int payloadVersion, String eventType, Map<String, Object> payload) {
        return new EventEnvelope("event-1", eventType, OCCURRED_AT_MILLIS, payloadVersion, payload);
    }

    private EventFixtures() {
    }
}
