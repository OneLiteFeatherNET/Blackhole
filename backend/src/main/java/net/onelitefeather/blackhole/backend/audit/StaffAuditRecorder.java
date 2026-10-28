package net.onelitefeather.blackhole.backend.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import io.micronaut.core.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records one staff action after it has succeeded. The payload is checked, the row is written, and only then is
 * the {@code staff.action} event published, so every event has a row. A failure is logged (without the payload),
 * counted and reported as {@link Outcome.Unrecorded}: it never throws, so the action it records is never rolled back.
 */
@Singleton
public class StaffAuditRecorder {

    /** Event type of a stored entry; the subject is {@code blackhole.events.staff.action}. */
    public static final String EVENT_TYPE = "staff.action";
    static final String FAILURE_METER = "blackhole_audit_write_failed_total";

    private static final Logger LOGGER = LoggerFactory.getLogger(StaffAuditRecorder.class);

    private final StaffAuditStore store;
    private final DomainEventPublisher events;
    private final AuditPayloadGuard guard;
    private final Clock clock;
    private final Counter failures;

    public StaffAuditRecorder(StaffAuditStore store, DomainEventPublisher events, AuditPayloadGuard guard, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.events = events;
        this.guard = guard;
        this.clock = clock;
        this.failures = Counter.builder(FAILURE_METER).description("Staff actions that could not be recorded in the audit log").register(meters);
    }

    /**
     * @param attribution whose action it is and which token made the call
     * @param actor       the verified caller (its surface and display name)
     * @return the outcome; never throws
     */
    public Outcome record(Attribution attribution, Actor actor, AuditAction action, AuditTarget target) {
        boolean onBehalfOf = !attribution.source().equals(attribution.recordedByOid());
        StaffAuditEntry entry = new StaffAuditEntry(UUID.randomUUID(), this.clock.millis(), attribution.source(),
                onBehalfOf ? null : actor.displayName(), SurfaceResolver.resolve(actor, onBehalfOf),
                action.wireName(), target.kind(), target.ref(), attribution.recordedByOid());
        Map<String, Object> payload = payloadOf(entry);
        try {
            this.guard.check(payload, entry.actorOid(), entry.auditId());
            this.store.insert(entry);
        } catch (RuntimeException e) {
            this.failures.increment();
            actor.receipt().markUnrecorded();
            LOGGER.warn("Staff action {} could not be recorded in the audit log: {}", action.wireName(), e.getClass().getSimpleName());
            return new Outcome.Unrecorded();
        }
        this.events.publish(EVENT_TYPE, entry.auditId().toString(), payload, failure -> { });
        return new Outcome.Recorded(entry.auditId());
    }

    private static Map<String, Object> payloadOf(StaffAuditEntry entry) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("auditId", entry.auditId().toString());
        payload.put("occurredAt", entry.occurredAt());
        payload.put("surface", entry.surface().name());
        payload.put("action", entry.action());
        payload.put("actorOid", entry.actorOid().toString());
        putIfPresent(payload, "actorName", entry.actorName());
        payload.put("targetKind", entry.targetKind());
        putIfPresent(payload, "targetRef", entry.targetRef());
        putIfPresent(payload, "panelPath", entry.panelPath());
        return payload;
    }

    private static void putIfPresent(Map<String, Object> payload, String key, @Nullable String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    /** What happened to the entry. */
    public sealed interface Outcome {

        record Recorded(UUID auditId) implements Outcome {
        }

        record Unrecorded() implements Outcome {
        }
    }
}
