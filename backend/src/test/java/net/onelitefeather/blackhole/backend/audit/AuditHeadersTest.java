package net.onelitefeather.blackhole.backend.audit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.http.HttpResponse;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuditHeadersTest {

    private static final UUID MOD = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final InMemoryStaffAuditStore store = new InMemoryStaffAuditStore();
    private final TestClock clock = new TestClock();
    private final StaffAuditRecorder recorder = new StaffAuditRecorder(store,
            new DomainEventPublisher(new RecordingEventTransport(), clock), new AuditPayloadGuard(), clock, new SimpleMeterRegistry());

    @Test
    void theResponseOfAnActionWhoseEntryFailedCarriesTheUnrecordedHeader() {
        Actor mod = new Actor(MOD, "Mod", ActorKind.HUMAN, false).withPanel(true);
        store.failInserts(true);
        recorder.record(mod.attribution(), mod, AuditAction.KICK_CREATE, AuditTarget.ofPlayer("a".repeat(128)));

        var response = AuditHeaders.audited(HttpResponse.ok("accepted"), mod);

        assertEquals("unrecorded", response.getHeaders().get(AuditHeaders.HEADER), "the action succeeded but its entry is missing");
    }

    @Test
    void theResponseOfARecordedActionHasNoAuditHeader() {
        Actor mod = new Actor(MOD, "Mod", ActorKind.HUMAN, false).withPanel(true);
        recorder.record(mod.attribution(), mod, AuditAction.KICK_CREATE, AuditTarget.ofPlayer("a".repeat(128)));

        var response = AuditHeaders.audited(HttpResponse.ok("accepted"), mod);

        assertNull(response.getHeaders().get(AuditHeaders.HEADER), "a stored entry is not flagged");
    }
}
