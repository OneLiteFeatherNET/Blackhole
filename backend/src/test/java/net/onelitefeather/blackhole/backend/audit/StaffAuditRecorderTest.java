package net.onelitefeather.blackhole.backend.audit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffAuditRecorderTest {

    private static final UUID MOD = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final InMemoryStaffAuditStore store = new InMemoryStaffAuditStore();
    private final RecordingEventTransport transport = new RecordingEventTransport();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TestClock clock = new TestClock();
    private final StaffAuditRecorder recorder = new StaffAuditRecorder(
            store, new DomainEventPublisher(transport, clock), new AuditPayloadGuard(), clock, meters);

    @Test
    void recordedActionIsStoredOnceWithSurfaceActionAndTarget() {
        Actor mod = new Actor(MOD, "Mod", ActorKind.HUMAN, false).withPanel(true);

        StaffAuditRecorder.Outcome outcome = recorder.record(mod.attribution(), mod, AuditAction.PUNISHMENT_REVOKE,
                AuditTarget.ofCase("AB12CD34"));

        assertInstanceOf(StaffAuditRecorder.Outcome.Recorded.class, outcome);
        assertEquals(1, store.rows().size(), "exactly one row stored");
        StaffAuditEntry row = store.rows().getFirst();
        assertEquals(Surface.PANEL, row.surface());
        assertEquals("punishment.revoke", row.action());
        assertEquals("CASE", row.targetKind());
        assertEquals("AB12CD34", row.targetRef());
        assertEquals(MOD, row.actorOid());
    }

    @Test
    void eventCarriesTheIdOfTheStoredRow() {
        Actor mod = new Actor(MOD, "Mod", ActorKind.HUMAN, false);

        recorder.record(mod.attribution(), mod, AuditAction.KICK_CREATE, AuditTarget.ofPlayer("a".repeat(128)));

        assertEquals(1, transport.published().size(), "exactly one event published");
        RecordingEventTransport.Published published = transport.published().getFirst();
        assertEquals("staff.action", published.eventType());
        assertEquals(store.rows().getFirst().auditId().toString(), published.event().eventId());
    }

    @Test
    void failedInsertIsReportedAsUnrecordedAndPublishesNothing() {
        Actor mod = new Actor(MOD, "Mod", ActorKind.HUMAN, false);
        store.failInserts(true);

        StaffAuditRecorder.Outcome outcome = recorder.record(mod.attribution(), mod, AuditAction.KICK_CREATE, AuditTarget.none());

        assertInstanceOf(StaffAuditRecorder.Outcome.Unrecorded.class, outcome);
        assertTrue(transport.published().isEmpty(), "no event without a stored row");
        assertEquals(1.0, meters.counter("blackhole_audit_write_failed_total").count());
    }

    @Test
    void proxyVouchingForStaffRecordsInGameWithStaffAsActor() {
        UUID proxyOid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Actor proxy = new Actor(proxyOid, null, ActorKind.SERVICE, true);
        Attribution attribution = proxy.attribution(MOD).orElseThrow();

        recorder.record(attribution, proxy, AuditAction.PUNISHMENT_REVOKE, AuditTarget.ofCase("AB12CD34"));

        StaffAuditEntry row = store.rows().getFirst();
        assertEquals(Surface.IN_GAME, row.surface());
        assertEquals(MOD, row.actorOid());
        assertEquals(proxyOid, row.recordedByOid());
        assertNull(row.actorName(), "no name snapshot for a vouched-for staff member");
        assertEquals(Optional.of("/cases/AB12CD34"), Optional.ofNullable(row.panelPath()));
    }
}
