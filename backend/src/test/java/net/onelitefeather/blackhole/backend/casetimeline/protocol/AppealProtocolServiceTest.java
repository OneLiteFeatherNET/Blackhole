package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceDeletionReason;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.service.GeneratedEvidenceGateway;
import net.onelitefeather.blackhole.backend.evidence.service.GeneratedEvidenceService;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.casetimeline.protocol.AppealProtocolGenerator.Outcome;
import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.APPEAL;
import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.PUNISHMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol service over in-memory stores and the real evidence gateway over an in-memory blob
 * store: a fixed clock, no database, no network, no sleeps.
 */
class AppealProtocolServiceTest {

    private static final Duration SETTLE = Duration.ofMinutes(1);
    private static final Attribution STAFF = new Attribution(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @TempDir
    Path spool;

    private EvidenceTestBed bed;
    private FakeProtocolSource source;
    private InMemoryProtocolStore store;
    private FlakyGateway gateway;
    private SimpleMeterRegistry meters;
    private AppealProtocolService service;

    /** The gateway of the real evidence service that can be told to fail a store. */
    private static final class FlakyGateway implements GeneratedEvidenceGateway {

        final GeneratedEvidenceGateway inner;
        boolean failStore;

        FlakyGateway(GeneratedEvidenceGateway inner) {
            this.inner = inner;
        }

        @Override
        public Stored store(String punishmentIdentifier, String baseName, byte[] text, byte[] json, Map<String, Object> metadata) throws StoreException {
            if (this.failStore) {
                throw new StoreException("down", false, null);
            }
            return this.inner.store(punishmentIdentifier, baseName, text, json, metadata);
        }

        @Override
        public Optional<Opened> open(UUID evidenceId, Variant variant, EvidenceAuditAction action, Attribution by) throws IOException {
            return this.inner.open(evidenceId, variant, action, by);
        }

        @Override
        public boolean delete(UUID evidenceId, EvidenceDeletionReason reason) throws IOException {
            return this.inner.delete(evidenceId, reason);
        }
    }

    @BeforeEach
    void givenAnAppealDecidedJustBeforeNow() {
        this.bed = new EvidenceTestBed(this.spool);
        this.bed.givenPunishment(PUNISHMENT);
        build(16 * 1024 * 1024);
        this.source = new FakeProtocolSource();
        this.store = new InMemoryProtocolStore();
        this.meters = new SimpleMeterRegistry();
        rebuildService();
    }

    private void build(long maxBytes) {
        this.gateway = new FlakyGateway(new GeneratedEvidenceService(this.bed.items.repository(), this.bed.audit.repository(),
                this.bed.punishments.repository(), this.bed.blobs, this.bed.events, this.bed.clock, maxBytes));
    }

    private void rebuildService() {
        this.service = new AppealProtocolService(this.source, this.store, this.gateway, this.bed.cases.timeline, this.bed.events, this.bed.clock,
                this.meters, SETTLE, 3);
    }

    private long now() {
        return this.bed.clock.millis();
    }

    /** A decided appeal whose conversation ended {@code ago} before now. */
    private void givenDecidedAppeal(Duration ago) {
        long end = now() - ago.toMillis();
        this.source.appeals.add(ProtocolFixtures.appeal(APPEAL, AppealStatus.DENIED, end - 3_000, end));
        this.source.entries.add(ProtocolFixtures.filed(1, end - 3_000, APPEAL));
        this.source.entries.add(ProtocolFixtures.playerMessage(2, end - 2_000, "m-1", APPEAL, "I did not cheat."));
        this.source.entries.add(ProtocolFixtures.staffMessage(3, end - 1_000, "m-2", APPEAL, "We looked at the logs.", "Reviewer Rita"));
        this.source.entries.add(ProtocolFixtures.decided(4, end, APPEAL, "DENIED"));
    }

    private List<EvidenceItemEntity> transcripts() {
        return this.bed.items.all().stream().filter(item -> item.getKind() == EvidenceKind.TRANSCRIPT).toList();
    }

    private String textOf(EvidenceItemEntity item) {
        return new String(this.bed.blobs.bytes(item.getFileKey()), StandardCharsets.UTF_8);
    }

    // ---- creation, idempotence, versions ----

    @Test
    void decidingStoresVersionOneAsTranscriptEvidenceWithAFilePair() {
        givenDecidedAppeal(Duration.ZERO);

        AppealProtocolGenerator.Result result = this.service.generate(APPEAL, true);

        assertEquals(Outcome.STORED, result.outcome());
        assertEquals(1, result.version());
        EvidenceItemEntity item = transcripts().getFirst();
        assertEquals(PUNISHMENT, item.getPunishmentIdentifier());
        assertEquals("blackhole", item.getSourceSystem());
        assertEquals(EvidenceStatus.VALID, item.getStatus());
        assertEquals(null, item.getCreatedBy(), "system generated, no creator");
        assertNotNull(this.bed.blobs.bytes(item.getFileKey()));
        assertNotNull(this.bed.blobs.bytes(item.getJsonFileKey()));
        assertEquals("text/plain", item.getFileContentType());
        assertTrue(textOf(item).contains("Version: 1"));
        assertEquals(ProtocolVersion.Status.ACTIVE, this.store.active(APPEAL).orElseThrow().status());
    }

    @Test
    void storingRecordsProtocolStoredWithoutContentAndPublishesTheDomainEvent() {
        givenDecidedAppeal(Duration.ZERO);

        AppealProtocolGenerator.Result result = this.service.generate(APPEAL, true);

        CaseEventRecord recorded = this.bed.cases.log.ofType(PUNISHMENT, "protocol.stored").getFirst();
        CaseEventPayload.ProtocolStored payload = assertInstanceOf(CaseEventPayload.ProtocolStored.class, recorded.payload());
        assertEquals(1, payload.version());
        assertEquals(4, payload.entries());
        assertEquals(result.evidenceId().toString(), payload.evidenceId());
        assertEquals("STAFF", recorded.visibility(), "never player visible");
        assertFalse(CaseEventPayload.PORTAL_VISIBLE_TYPES.contains("protocol.stored"));
        assertFalse(recorded.body().toString().contains("did not cheat"), "no content in the event");

        RecordingEventPublisher.Event event = this.bed.events.eventsOfType("case.protocol_stored").getFirst();
        assertEquals(Map.of("appealId", APPEAL.toString(), "version", 1, "evidenceId", result.evidenceId().toString(), "entries", 4),
                Map.of("appealId", event.payload().get("appealId"), "version", event.payload().get("version"),
                        "evidenceId", event.payload().get("evidenceId"), "entries", event.payload().get("entries")));
        assertFalse(event.payload().toString().contains("did not cheat"), "no content in the event");
    }

    @Test
    void generatingAgainWithoutChangesStoresNothingAndNamesTheExistingVersion() {
        givenDecidedAppeal(Duration.ZERO);
        AppealProtocolGenerator.Result first = this.service.generate(APPEAL, true);

        AppealProtocolGenerator.Result again = this.service.generate(APPEAL, true);

        assertEquals(Outcome.UNCHANGED, again.outcome());
        assertEquals(1, again.version());
        assertEquals(first.evidenceId(), again.evidenceId());
        assertEquals(1, transcripts().size());
        assertEquals(1, this.store.rows.size());
    }

    @Test
    void aLateEditStoresVersionTwoAndSupersedesTheFirst() {
        givenDecidedAppeal(Duration.ZERO);
        this.service.generate(APPEAL, true);
        this.source.entries.add(ProtocolFixtures.edited(5, now(), "m-2", "We looked again.", "Reviewer Rita"));
        this.bed.clock.advance(Duration.ofMinutes(5));

        AppealProtocolGenerator.Result result = this.service.generate(APPEAL, false);

        assertEquals(Outcome.STORED, result.outcome());
        assertEquals(2, result.version());
        assertEquals(ProtocolVersion.Status.SUPERSEDED, this.store.rows.getFirst().status());
        assertEquals(ProtocolVersion.Status.ACTIVE, this.store.active(APPEAL).orElseThrow().status());
        assertEquals(2, transcripts().size(), "both versions are kept and listed");
        assertTrue(textOf(transcripts().get(1)).contains("We looked again."));
    }

    @Test
    void aChangeInsideTheSettleDelayWaitsUnlessForced() {
        givenDecidedAppeal(Duration.ZERO);
        this.service.generate(APPEAL, true);
        this.bed.clock.advance(Duration.ofSeconds(10));
        this.source.entries.add(ProtocolFixtures.staffMessage(5, now() - 1_000, "m-3", APPEAL, "One more thing.", "Reviewer Rita"));

        assertEquals(Outcome.NOT_SETTLED, this.service.generate(APPEAL, false).outcome());
        assertEquals(1, transcripts().size());

        this.bed.clock.advance(SETTLE);
        assertEquals(Outcome.STORED, this.service.generate(APPEAL, false).outcome(), "stored once the conversation was quiet");
    }

    @Test
    void forcingSkipsTheSettleDelay() {
        givenDecidedAppeal(Duration.ZERO);
        this.service.generate(APPEAL, true);
        this.source.entries.add(ProtocolFixtures.staffMessage(5, now(), "m-3", APPEAL, "Just now.", "Reviewer Rita"));

        AppealProtocolGenerator.Result forced = this.service.generate(APPEAL, true);

        assertEquals(Outcome.STORED, forced.outcome());
        assertEquals(2, forced.version());
    }

    @Test
    void aLostRaceRemovesItsOwnItemAndStoresOnTheNextLook() {
        givenDecidedAppeal(Duration.ZERO);
        this.store.loseNextAdd = true;

        AppealProtocolGenerator.Result result = this.service.generate(APPEAL, true);

        assertEquals(Outcome.STORED, result.outcome());
        assertEquals(1, transcripts().size(), "the item of the lost run was removed again");
        assertEquals(2, this.bed.blobs.keys().size(), "and so were its files");
    }

    // ---- no protocol ----

    @Test
    void anUndecidedAppealGetsNoProtocol() {
        this.source.appeals.add(ProtocolFixtures.appeal(APPEAL, AppealStatus.IN_REVIEW, 1_000, 1_000));

        assertEquals(Outcome.NOT_DECIDED, this.service.generate(APPEAL, true).outcome());
        assertTrue(transcripts().isEmpty());
    }

    @Test
    void anUnknownAppealIsNotFound() {
        assertEquals(Outcome.NOT_FOUND, this.service.generate(UUID.randomUUID(), true).outcome());
    }

    @Test
    void aTicketWhoseTranscriptIsNotVerifiedCompleteGetsNoProtocolEvenWhenForced() {
        givenDecidedAppeal(Duration.ZERO);
        this.source.entries.add(ProtocolFixtures.raw(5, now() - 2_500, "ticket.opened", ActorTrust.SYSTEM, Map.of("ticketId", "t-1")));

        assertEquals(Outcome.TICKET_INCOMPLETE, this.service.generate(APPEAL, true).outcome());
        assertTrue(transcripts().isEmpty());

        this.source.entries.add(ProtocolFixtures.raw(6, now() - 500, "ticket.transcript_complete", ActorTrust.SYSTEM,
                Map.of("ticketId", "t-1", "count", 3, "lastMessageId", "42")));
        assertEquals(Outcome.STORED, this.service.generate(APPEAL, true).outcome());
    }

    @Test
    void generationNeedsNothingButTheStoredEvents() {
        givenDecidedAppeal(Duration.ZERO);
        // The source interface offers appeal rows and case events only; with only those present it completes.
        assertEquals(Outcome.STORED, this.service.generate(APPEAL, true).outcome());
    }

    // ---- failures ----

    @Test
    void anOversizedProtocolStoresNothingAndCountsAFailure() {
        givenDecidedAppeal(Duration.ZERO);
        build(10);
        rebuildService();

        AppealProtocolGenerator.Result result = this.service.generate(APPEAL, true);

        assertEquals(Outcome.TOO_LARGE, result.outcome());
        assertTrue(transcripts().isEmpty());
        assertTrue(this.store.rows.isEmpty());
        assertEquals(1.0, this.meters.get(AppealProtocolService.FAILURE_METER).tag("reason", "too_large").counter().count());
    }

    @Test
    void aFailingFileStoreNeverThrowsAndLeavesNoRow() {
        givenDecidedAppeal(Duration.ZERO);
        this.bed.blobs.fail();

        AppealProtocolGenerator.Result result = this.service.generate(APPEAL, true);

        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(this.store.rows.isEmpty());
        assertTrue(transcripts().isEmpty());
    }

    @Test
    void aFailingTimelineRecordingDoesNotFailTheStoredProtocol() {
        givenDecidedAppeal(Duration.ZERO);
        this.bed.cases.log.failAppends(new IllegalStateException("db down"));

        assertEquals(Outcome.STORED, this.service.generate(APPEAL, true).outcome());
        assertEquals(1.0, this.bed.cases.failures());
    }

    // ---- redaction ----

    @Test
    void aRedactionMakesEveryVersionUnreadableDeletesItsFilesAndRegeneratesWithAMarker() {
        givenDecidedAppeal(Duration.ZERO);
        this.service.generate(APPEAL, true);
        this.source.entries.add(ProtocolFixtures.edited(5, now(), "m-2", "Edited reply.", "Reviewer Rita"));
        this.service.generate(APPEAL, true);
        List<EvidenceItemEntity> before = transcripts();
        assertEquals(2, before.size());

        // The redaction statement marks the versions and redacts the rows; then the service finishes the job.
        this.store.markPurgePending(PUNISHMENT);
        CaseReader.Entry player = this.source.entries.get(1);
        this.source.entries.set(1, ProtocolFixtures.redacted(player));
        this.source.entries.add(ProtocolFixtures.redactedMarker(6, now(), "m-1"));
        assertTrue(this.store.rows.stream().noneMatch(row -> row.status().readable()), "unreadable at once, before anything is deleted");

        this.service.afterRedaction(PUNISHMENT);

        assertEquals(1, transcripts().size(), "the purged items and their files are gone");
        assertEquals(2, this.bed.blobs.keys().size());
        assertEquals(3, this.store.active(APPEAL).orElseThrow().version(), "a new version, numbers are never reused");
        assertFalse(textOf(transcripts().getFirst()).contains("I did not cheat"));
        assertTrue(textOf(transcripts().getFirst()).contains(ProtocolRenderer.REMOVED));
        List<EvidenceAuditEntity> deletions = this.bed.audit.all().stream().filter(line -> line.getAction() == EvidenceAuditAction.DELETED).toList();
        assertEquals(2, deletions.size());
        assertTrue(deletions.stream().allMatch(line -> EvidenceDeletionReason.ERASURE.name().equals(line.getReason())));
        assertEquals(2, this.store.rows.stream().filter(row -> row.status() == ProtocolVersion.Status.PURGED).count());
    }

    @Test
    void aFailedRegenerationKeepsTheOldVersionsUnreadableAndTheReconcilerStoresTheNewOne() {
        givenDecidedAppeal(Duration.ZERO);
        this.service.generate(APPEAL, true);
        this.store.markPurgePending(PUNISHMENT);
        this.source.entries.set(1, ProtocolFixtures.redacted(this.source.entries.get(1)));
        this.gateway.failStore = true;

        this.service.afterRedaction(PUNISHMENT);

        assertTrue(this.store.active(APPEAL).isEmpty(), "no old text is readable");
        assertTrue(this.store.rows.stream().noneMatch(row -> row.status().readable()));
        assertTrue(transcripts().isEmpty(), "the old files were still purged");

        this.gateway.failStore = false;
        this.store.candidates = List.of(APPEAL);
        this.bed.clock.advance(SETTLE);
        int stored = new ProtocolReconciler(this.service, this.store, 50).reconcile();

        assertEquals(1, stored);
        assertEquals(2, this.store.active(APPEAL).orElseThrow().version());
    }

    @Test
    void anAnonymisedStaffActorPurgesAndRegeneratesWithTheNameGone() {
        givenDecidedAppeal(Duration.ZERO);
        this.service.generate(APPEAL, true);
        assertTrue(textOf(transcripts().getFirst()).contains("Reviewer Rita"));
        this.source.entries.replaceAll(entry -> entry.actorName() == null ? entry : new CaseReader.Entry(entry.event(), null));

        this.service.afterStaffAnonymized(UUID.randomUUID());

        assertEquals(1, transcripts().size());
        String text = textOf(transcripts().getFirst());
        assertFalse(text.contains("Reviewer Rita"));
        assertTrue(text.contains(ProtocolModel.ANONYMOUS_STAFF_LABEL));
        assertEquals(2, this.store.active(APPEAL).orElseThrow().version());
    }

    // ---- reconciler ----

    @Test
    void theReconcilerCreatesAMissingProtocolOfADecidedAppeal() {
        givenDecidedAppeal(Duration.ofMinutes(10));
        this.store.candidates = List.of(APPEAL);

        int stored = new ProtocolReconciler(this.service, this.store, 50).reconcile();

        assertEquals(1, stored);
        assertEquals(1, this.store.active(APPEAL).orElseThrow().version());
    }

    @Test
    void theReconcilerWaitsForALateMessageToSettleThenStoresANewVersion() {
        givenDecidedAppeal(Duration.ofMinutes(10));
        this.service.generate(APPEAL, true);
        this.source.entries.add(ProtocolFixtures.staffMessage(5, now(), "m-3", APPEAL, "After the decision.", "Reviewer Rita"));
        this.store.candidates = List.of(APPEAL);
        ProtocolReconciler reconciler = new ProtocolReconciler(this.service, this.store, 50);

        assertEquals(0, reconciler.reconcile(), "inside the settle delay");
        this.bed.clock.advance(SETTLE);
        assertEquals(1, reconciler.reconcile(), "after it");
        assertEquals(2, this.store.active(APPEAL).orElseThrow().version());
        assertEquals(0, reconciler.reconcile(), "and nothing more once it is current");
    }

    // ---- evidence rules for the kind ----

    @Test
    void aTranscriptIsNotCountedAndNotMarkedWhenThePunishmentIsLiftedOrRevoked() {
        givenDecidedAppeal(Duration.ZERO);
        this.service.generate(APPEAL, true);

        assertEquals(0, this.bed.service.countValid(PUNISHMENT), "not part of the evidence count");
        this.bed.service.markLifted(PUNISHMENT, APPEAL, STAFF);
        this.bed.service.markRevoked(PUNISHMENT, STAFF);

        assertEquals(EvidenceStatus.VALID, transcripts().getFirst().getStatus());
        assertEquals(1, ((EvidenceDTO.PageResponse) this.bed.service.list(PUNISHMENT, false,
                io.micronaut.data.model.Pageable.from(0, 10), EvidenceTestBed.ACTOR)).items().size(), "still listed by default");
    }

    @Test
    void theAttachRouteRejectsTheTranscriptKind() {
        EvidenceDTO result = this.bed.service.attach(new EvidenceDTO.CreateRequest(PUNISHMENT, null, null, EvidenceKind.TRANSCRIPT, "client", null, null,
                Map.of("a", "b"), null), EvidenceTestBed.ACTOR);

        assertEquals(EvidenceErrorKind.VALIDATION, assertInstanceOf(EvidenceDTO.Error.class, result).kind());
        assertTrue(transcripts().isEmpty());
    }

    @Test
    void aContentLineWithAnIpAddressIsStoredUnchanged() {
        givenDecidedAppeal(Duration.ofMinutes(10));
        this.source.entries.set(1, ProtocolFixtures.playerMessage(2, now() - 2_000, "m-1", APPEAL, "my ip is 203.0.113.9"));

        this.service.generate(APPEAL, true);

        assertTrue(textOf(transcripts().getFirst()).contains("203.0.113.9"), "the guards do not rewrite the record");
    }

    @Test
    void openingAndDownloadingAreAuditedWithoutContent() throws IOException {
        givenDecidedAppeal(Duration.ZERO);
        UUID evidenceId = this.service.generate(APPEAL, true).evidenceId();

        GeneratedEvidenceGateway.Opened text = this.gateway.open(evidenceId, GeneratedEvidenceGateway.Variant.TEXT, EvidenceAuditAction.VIEWED, STAFF).orElseThrow();
        GeneratedEvidenceGateway.Opened json = this.gateway.open(evidenceId, GeneratedEvidenceGateway.Variant.JSON, EvidenceAuditAction.DOWNLOADED, STAFF).orElseThrow();

        assertTrue(new String(text.bytes(), StandardCharsets.UTF_8).startsWith("Appeal protocol"));
        assertEquals("application/json", json.contentType());
        assertTrue(json.fileName().endsWith(".json"));
        List<EvidenceAuditAction> actions = this.bed.audit.all().stream().map(EvidenceAuditEntity::getAction).toList();
        assertTrue(actions.contains(EvidenceAuditAction.VIEWED) && actions.contains(EvidenceAuditAction.DOWNLOADED), actions.toString());
        assertTrue(this.bed.audit.all().stream().allMatch(line -> line.getNote() == null), "audit lines carry no content");
    }
}
