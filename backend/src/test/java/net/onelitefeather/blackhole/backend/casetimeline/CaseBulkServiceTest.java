package net.onelitefeather.blackhole.backend.casetimeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseBulkOutcome;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseBulkService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import net.onelitefeather.blackhole.backend.support.FakeCaseStore;
import net.onelitefeather.blackhole.backend.support.InMemoryCaseEventRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffAuditStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bulk edit over in-memory stores: validation, one outcome per case, audit and events. No database, no clock reads. */
class CaseBulkServiceTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String A = "CASE-A";
    private static final String B = "CASE-B";
    private static final String C = "CASE-C";
    private static final Actor MODERATOR = new Actor(UUID.nameUUIDFromBytes("moderator".getBytes()), "Moderator", ActorKind.HUMAN, false);
    private static final UUID ASSIGNEE = UUID.nameUUIDFromBytes("assignee".getBytes());

    private final TestClock clock = new TestClock();
    private final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    private final FakeCaseStore store = new FakeCaseStore(log);
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier)
                    .build();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> staff =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final InMemoryStaffAuditStore auditStore = new InMemoryStaffAuditStore();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StaffAuditRecorder audit = new StaffAuditRecorder(this.auditStore,
            new DomainEventPublisher(new RecordingEventTransport(), this.clock), new AuditPayloadGuard(), this.clock, this.meters);
    private final CaseBulkService service = new CaseBulkService(
            this.punishments.repository(), this.staff.repository(), this.store, this.store, this.events, this.clock, this.audit, this.meters);

    private void givenPunishment(String identifier) {
        this.punishments.given(new PunishmentEntity(identifier, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), identifier);
    }

    private void givenKnownHuman(UUID oid) {
        this.staff.given(new StaffActorEntity(oid, ActorKind.HUMAN, "Assignee", 1L), oid);
    }

    /** A case at version 1 in the given status; a case in the default state keeps no row and version 0. */
    private void givenCaseIn(String identifier, String status) {
        this.store.ensureCase(identifier, 1L);
        if (!status.equals(CaseMeta.DEFAULT_STATUS)) {
            this.store.update(identifier, 0L, 1L, CaseActor.of(MODERATOR.attribution()), CaseMeta.defaults(),
                    new CaseMeta(status, null, CaseMeta.DEFAULT_PRIORITY, List.of()));
        }
    }

    private static CaseDTO.BulkCase at(String identifier, long version) {
        return new CaseDTO.BulkCase(identifier, version);
    }

    private static CaseDTO.BulkRequest closeAll(List<CaseDTO.BulkCase> cases) {
        return new CaseDTO.BulkRequest(cases, CaseStatus.CLOSED, null, false, null, null, null);
    }

    private CaseDTO run(CaseDTO.BulkRequest request) {
        return this.service.bulk(request, MODERATOR);
    }

    private static CaseDTO.BulkItem itemOf(CaseDTO.BulkResult result, String identifier) {
        return result.items().stream().filter(item -> item.punishmentIdentifier().equals(identifier)).findFirst().orElseThrow();
    }

    @Test
    void aBulkEditOfNoCasesIsRefusedAndWritesNothing() {
        assertEquals(CaseErrorKind.VALIDATION, assertInstanceOf(CaseDTO.Error.class, run(closeAll(List.of()))).kind());
        assertTrue(this.log.all().isEmpty());
    }

    @Test
    void aBulkEditOfMoreThanOneHundredCasesIsRefusedAndWritesNothing() {
        List<CaseDTO.BulkCase> cases = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            String identifier = "CASE-" + i;
            givenPunishment(identifier);
            cases.add(at(identifier, 0));
        }

        assertEquals(CaseErrorKind.VALIDATION, assertInstanceOf(CaseDTO.Error.class, run(closeAll(cases))).kind());
        assertTrue(this.log.all().isEmpty());
    }

    @Test
    void aBulkEditListingTheSameCaseTwiceIsRefusedAndWritesNothing() {
        givenPunishment(A);

        assertEquals(CaseErrorKind.VALIDATION, assertInstanceOf(CaseDTO.Error.class, run(closeAll(List.of(at(A, 0), at(A, 0))))).kind());
        assertTrue(this.log.all().isEmpty());
    }

    @Test
    void aBulkEditWithoutAnyChangeIsRefused() {
        givenPunishment(A);

        CaseDTO result = run(new CaseDTO.BulkRequest(List.of(at(A, 0)), null, null, false, null, null, null));

        assertEquals(CaseErrorKind.VALIDATION, assertInstanceOf(CaseDTO.Error.class, result).kind());
    }

    @Test
    void aBulkEditWithAnUnknownAssigneeIsRefusedBeforeAnyCaseIsWritten() {
        givenPunishment(A);
        givenPunishment(B);

        CaseDTO result = run(new CaseDTO.BulkRequest(List.of(at(A, 0), at(B, 0)), null, UUID.randomUUID(), false, null, null, null));

        assertEquals(CaseErrorKind.UNPROCESSABLE, assertInstanceOf(CaseDTO.Error.class, result).kind());
        assertTrue(this.log.all().isEmpty(), "a request-level error changes no case");
    }

    @Test
    void aBulkEditWithAnAssigneeAndAClearIsRefused() {
        givenPunishment(A);
        givenKnownHuman(ASSIGNEE);

        CaseDTO result = run(new CaseDTO.BulkRequest(List.of(at(A, 0)), null, ASSIGNEE, true, null, null, null));

        assertEquals(CaseErrorKind.VALIDATION, assertInstanceOf(CaseDTO.Error.class, result).kind());
    }

    @Test
    void theBulkRequestHasNoOnBehalfOfField() {
        boolean named = java.util.Arrays.stream(CaseDTO.BulkRequest.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("onBehalfOf"));

        assertFalse(named, "a bulk edit is always the caller's own, so the delegation field must not exist");
    }

    @Test
    void aChangedCaseIsReportedUpdatedWithItsNewVersion() {
        givenPunishment(A);

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at(A, 0)))));

        CaseDTO.BulkItem item = itemOf(result, A);
        assertEquals(CaseBulkOutcome.UPDATED, item.outcome());
        assertTrue(item.changed());
        assertEquals(1L, item.version());
        assertEquals(CaseStatus.CLOSED.name(), this.store.meta(A).status());
    }

    @Test
    void aCaseAlreadyInTheRequestedStateIsUnchangedAndAppendsNothing() {
        givenPunishment(A);
        givenCaseIn(A, "CLOSED");

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at(A, 1)))));

        assertEquals(CaseBulkOutcome.UNCHANGED, itemOf(result, A).outcome());
        assertFalse(itemOf(result, A).changed());
        assertEquals(1, result.unchanged());
        assertEquals(1, this.log.all().size(), "only the event of the given state, nothing new");
    }

    @Test
    void aStaleVersionIsAConflictWithTheCurrentVersionAndTheOtherCasesStillChange() {
        givenPunishment(A);
        givenPunishment(B);
        givenPunishment(C);
        givenCaseIn(B, "OPEN");
        this.store.bumpVersion(B);

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at(A, 0), at(B, 0), at(C, 0)))));

        CaseDTO.BulkItem conflict = itemOf(result, B);
        assertEquals(CaseBulkOutcome.CONFLICT, conflict.outcome());
        assertEquals(Long.valueOf(1L), conflict.currentVersion());
        assertFalse(conflict.changed());
        assertEquals(CaseBulkOutcome.UPDATED, itemOf(result, A).outcome(), "a conflict does not stop the batch");
        assertEquals(CaseBulkOutcome.UPDATED, itemOf(result, C).outcome());
        assertEquals(1, result.conflicts());
        assertEquals(2, result.updated());
    }

    @Test
    void anUnknownPunishmentIsNotFoundAndTheOthersStillChange() {
        givenPunishment(A);

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at("NOPE", 0), at(A, 0)))));

        assertEquals(CaseBulkOutcome.NOT_FOUND, itemOf(result, "NOPE").outcome());
        assertEquals(CaseBulkOutcome.UPDATED, itemOf(result, A).outcome());
        assertEquals(1, result.notFound());
    }

    @Test
    void aCaseThatWouldExceedTheTagMaximumFailsAloneWithAMessage() {
        givenPunishment(A);
        givenPunishment(B);
        List<String> ten = List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10");
        this.store.ensureCase(A, 1L);
        this.store.update(A, 0L, 1L, CaseActor.of(MODERATOR.attribution()), CaseMeta.defaults(),
                new CaseMeta(CaseMeta.DEFAULT_STATUS, null, CaseMeta.DEFAULT_PRIORITY, ten));

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(
                new CaseDTO.BulkRequest(List.of(at(A, 1), at(B, 0)), null, null, false, null, List.of("extra"), null)));

        CaseDTO.BulkItem failed = itemOf(result, A);
        assertEquals(CaseBulkOutcome.FAILED, failed.outcome());
        assertFalse(failed.changed());
        assertFalse(failed.message() == null || failed.message().isBlank(), "a failed case says why");
        assertEquals(CaseBulkOutcome.UPDATED, itemOf(result, B).outcome(), "B has room for the tag, so the batch goes on for it");
        assertEquals(1, result.failed());
    }

    @Test
    void aStorageFailureOnOneCaseFailsThatCaseOnlyAndTheRestAreAttempted() {
        givenPunishment(A);
        givenPunishment(B);
        this.store.failBulkEditOf(B);

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at(A, 0), at(B, 0)))));

        assertEquals(CaseBulkOutcome.FAILED, itemOf(result, B).outcome());
        assertFalse(itemOf(result, B).changed());
        assertEquals(CaseBulkOutcome.UPDATED, itemOf(result, A).outcome(), "the case before the failure stays changed");
    }

    @Test
    void aChangedCaseGetsOneBulkEditedEventWithTheBatchId() {
        givenPunishment(A);

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at(A, 0)))));

        List<CaseEventRecord> bulk = this.log.all().stream().filter(event -> event.type().equals(CaseEventPayload.CASE_BULK_EDITED)).toList();
        assertEquals(1, bulk.size(), "one event for the changed case");
        CaseEventPayload.BulkEdited payload = assertInstanceOf(CaseEventPayload.BulkEdited.class, bulk.getFirst().payload());
        assertEquals(result.batchId(), payload.batchId());
        assertEquals("STATUS", payload.changes().getFirst().get("field"));
    }

    @Test
    void anUnchangedCaseGetsNoAuditEntryButAChangedOneDoes() {
        givenPunishment(A);
        givenPunishment(B);
        givenCaseIn(B, "CLOSED");

        run(closeAll(List.of(at(A, 0), at(B, 1))));

        assertEquals(1, this.auditStore.rows().size(), "only A changed");
        assertEquals("case.bulk_update", this.auditStore.rows().getFirst().action());
    }

    @Test
    void anAuditFailureAfterAChangeReportsChangedWithoutAuditWritten() {
        givenPunishment(A);
        this.auditStore.failInserts(true);

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at(A, 0)))));

        CaseDTO.BulkItem item = itemOf(result, A);
        assertEquals(CaseBulkOutcome.UPDATED, item.outcome(), "the case did change, so it is not reported as failed");
        assertTrue(item.changed());
        assertFalse(item.auditWritten());
        assertEquals(1.0, this.meters.counter("blackhole.case.bulk.audit-failures").count());
    }

    @Test
    void aChangedCaseSendsItsFieldEventsWithTheBatchId() {
        givenPunishment(A);

        CaseDTO.BulkResult result = assertInstanceOf(CaseDTO.BulkResult.class, run(closeAll(List.of(at(A, 0)))));

        Optional<RecordingEventPublisher.Event> status = this.events.eventsOfType("case.status_changed").stream().findFirst();
        assertTrue(status.isPresent(), "the domain event of the status change is still published");
        assertEquals(result.batchId(), status.get().payload().get("batchId"));
        assertNull(status.get().payload().get("text"));
    }
}
