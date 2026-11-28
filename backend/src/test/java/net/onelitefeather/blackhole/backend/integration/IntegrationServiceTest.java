package net.onelitefeather.blackhole.backend.integration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationServiceTest {

    private static final Actor ADMIN = new Actor(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "Admin", ActorKind.HUMAN, false, true);
    private static final String CLIENT = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b";
    private static final String OTHER_CLIENT = "7a1d0e2f-3b4c-4d5e-8f6a-9b0c1d2e3f40";

    private final FakeStore store = new FakeStore();
    private final TestClock clock = new TestClock();
    private final InMemoryStaffAuditStore auditStore = new InMemoryStaffAuditStore();
    private final IntegrationService service = new IntegrationService(store, clock,
            new StaffAuditRecorder(auditStore, new DomainEventPublisher(new RecordingEventTransport(), clock),
                    new AuditPayloadGuard(), clock, new SimpleMeterRegistry()));

    private static IntegrationDTO.CreateRequest vulcan(String clientId) {
        return new IntegrationDTO.CreateRequest("vulcan", "Vulcan bridge", clientId, List.of("DETECTION:1"), List.of("Lobby"), List.of());
    }

    private static IntegrationDTO.UpdateRequest editable(boolean enabled, List<String> tasks) {
        return new IntegrationDTO.UpdateRequest("Vulcan bridge", enabled, List.of("DETECTION:1"), tasks, List.of());
    }

    @Test
    void aCreatedRegistrationIsStoredEnabledWithItsScope() {
        IntegrationDTO result = service.create(vulcan(CLIENT), ADMIN);

        IntegrationDTO.Response stored = assertInstanceOf(IntegrationDTO.Response.class, result);
        assertTrue(stored.enabled(), "a new registration is enabled");
        assertEquals(List.of("Lobby"), stored.allowedTasks());
        assertEquals(CLIENT, stored.entraClientId());
    }

    @Test
    void aCreateWritesExactlyOneAuditEntryNamedBySourceSystem() {
        service.create(vulcan(CLIENT), ADMIN);

        assertEquals(1, auditStore.rows().size(), "one entry for the registration");
        assertEquals("integration.registered", auditStore.rows().getFirst().action());
        assertEquals("vulcan", auditStore.rows().getFirst().targetRef(), "the source system names the target, never a client id");
    }

    @Test
    void aSecondRegistrationForTheSameClientIdIsAConflict() {
        service.create(vulcan(CLIENT), ADMIN);

        IntegrationDTO second = service.create(
                new IntegrationDTO.CreateRequest("grim", "Grim", CLIENT, List.of("DETECTION:1"), List.of("Lobby"), List.of()), ADMIN);

        assertConflict(second);
        assertEquals(1, store.list().size(), "nothing new is stored");
    }

    @Test
    void aSecondRegistrationForTheSameSourceSystemIsAConflict() {
        service.create(vulcan(CLIENT), ADMIN);

        assertConflict(service.create(vulcan(OTHER_CLIENT), ADMIN));
    }

    @Test
    void anInvalidRegistrationIsAValidationErrorAndNothingIsStoredOrAudited() {
        IntegrationDTO result = service.create(
                new IntegrationDTO.CreateRequest("vulcan", "Vulcan", CLIENT, List.of("DETECTION:1"), List.of(), List.of()), ADMIN);

        IntegrationDTO.Error error = assertInstanceOf(IntegrationDTO.Error.class, result);
        assertEquals(ErrorKind.VALIDATION, error.kind());
        assertTrue(store.list().isEmpty());
        assertTrue(auditStore.rows().isEmpty());
    }

    @Test
    void disablingWritesADisableAuditEntryAndAnyOtherChangeWritesAnUpdate() {
        long id = ((IntegrationDTO.Response) service.create(vulcan(CLIENT), ADMIN)).id();

        service.update(id, editable(true, List.of("Lobby", "Survival")), ADMIN);
        service.update(id, editable(false, List.of("Lobby", "Survival")), ADMIN);

        assertEquals(List.of("integration.registered", "integration.updated", "integration.disabled"),
                auditStore.rows().stream().map(row -> row.action()).toList());
    }

    @Test
    void updatingAnUnknownRegistrationIsNotFound() {
        IntegrationDTO result = service.update(999L, editable(true, List.of("Lobby")), ADMIN);

        assertEquals(ErrorKind.NOT_FOUND, assertInstanceOf(IntegrationDTO.Error.class, result).kind());
    }

    @Test
    void theSourceSystemAndClientIdDoNotChangeOnUpdate() {
        long id = ((IntegrationDTO.Response) service.create(vulcan(CLIENT), ADMIN)).id();

        IntegrationDTO.Response updated = assertInstanceOf(IntegrationDTO.Response.class,
                service.update(id, editable(true, List.of("Creative")), ADMIN));

        assertEquals("vulcan", updated.sourceSystem());
        assertEquals(CLIENT, updated.entraClientId());
        assertEquals(List.of("Creative"), updated.allowedTasks());
        assertFalse(auditStore.rows().isEmpty());
    }

    private static void assertConflict(IntegrationDTO result) {
        assertEquals(ErrorKind.CONFLICT, assertInstanceOf(IntegrationDTO.Error.class, result).kind());
    }

    /** Keeps rows in memory, with the same uniqueness rules as the table. */
    static final class FakeStore implements IntegrationStore {

        private final Map<Long, Stored> rows = new ConcurrentHashMap<>();
        private long nextId = 1;

        @Override
        public Optional<Stored> find(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Stored> findByClientId(String entraClientId) {
            return rows.values().stream()
                    .filter(row -> row.spec().enabled() && row.spec().entraClientId().equals(entraClientId))
                    .findFirst();
        }

        @Override
        public List<Stored> list() {
            return rows.values().stream().sorted((a, b) -> a.spec().sourceSystem().compareTo(b.spec().sourceSystem())).toList();
        }

        @Override
        public synchronized OptionalLong insert(IntegrationSpec spec, UUID actorOid, long now) {
            boolean taken = rows.values().stream().anyMatch(row -> row.spec().sourceSystem().equals(spec.sourceSystem())
                    || row.spec().entraClientId().equals(spec.entraClientId()));
            if (taken) {
                return OptionalLong.empty();
            }
            long id = this.nextId++;
            rows.put(id, new Stored(id, spec, now, now));
            return OptionalLong.of(id);
        }

        @Override
        public synchronized boolean update(long id, IntegrationSpec spec, UUID actorOid, long now) {
            Stored current = rows.get(id);
            if (current == null) {
                return false;
            }
            rows.put(id, new Stored(id, spec, current.createdAt(), now));
            return true;
        }
    }
}
