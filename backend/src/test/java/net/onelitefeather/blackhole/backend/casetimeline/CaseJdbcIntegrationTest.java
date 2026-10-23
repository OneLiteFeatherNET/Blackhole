package net.onelitefeather.blackhole.backend.casetimeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JDBC side of the case log against a real PostgreSQL schema, migrated by the real migrations. */
@Tag("integration")
class CaseJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID REVIEWER = UUID.nameUUIDFromBytes("reviewer".getBytes());

    private final TestClock clock = new TestClock();
    private CaseDatabase db;
    private JdbcCaseEventRepository events;
    private JdbcCaseEventReader reader;
    private CaseTimeline timeline;

    @BeforeEach
    void givenAMigratedSchema() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        events = new JdbcCaseEventRepository(db.dataSource);
        reader = new JdbcCaseEventReader(db.dataSource);
        timeline = new CaseTimeline(events, new JdbcCaseWriter(db.dataSource), clock, new SimpleMeterRegistry());
    }

    private void givenPunishment(String id, long createdAt, String metaData) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, ?, 'network', false, false, ?, 'ABCDEF01', ?::jsonb)", id, STAFF, STAFF, OWNER, createdAt, metaData);
    }

    private CaseEventDraft expired(String punishment, String dedupeKey, long occurredAt) {
        return new CaseEventDraft(punishment, new CaseEventPayload.Expired(occurredAt), occurredAt, CaseEventOrigin.LIVE,
                CaseActor.system(), null, dedupeKey);
    }

    @Test
    void theFirstEventCreatesTheCaseWithDefaults() {
        givenPunishment("p-1", 1L, "{}");

        assertTrue(events.append(expired("p-1", "expired:p-1", 50)));

        assertEquals("OPEN", db.text("SELECT status FROM punishment_case WHERE punishment_identifier = 'p-1'"));
        assertEquals("NORMAL", db.text("SELECT priority FROM punishment_case WHERE punishment_identifier = 'p-1'"));
        assertEquals("[]", db.text("SELECT tags::text FROM punishment_case WHERE punishment_identifier = 'p-1'"));
        assertEquals(0, db.count("SELECT version FROM punishment_case WHERE punishment_identifier = 'p-1'"));
    }

    @Test
    void aSecondEventDoesNotCreateASecondCase() {
        givenPunishment("p-1", 1L, "{}");

        events.append(expired("p-1", "a", 50));
        events.append(expired("p-1", "b", 60));

        assertEquals(1, db.count("SELECT count(*) FROM punishment_case"));
        assertEquals(2, db.count("SELECT count(*) FROM case_event"));
    }

    @Test
    void anAppendedEventIsReadBackWithEveryField() {
        givenPunishment("p-1", 1L, "{}");
        CaseActor actor = new CaseActor(ActorTrust.UNVERIFIED, null, REVIEWER, STAFF);
        events.append(new CaseEventDraft("p-1", new CaseEventPayload.AppealFiled("a-1", true), 77, CaseEventOrigin.RECONSTRUCTED,
                actor, "a-1", "appeal.filed:a-1"));

        CaseEventRecord read = reader.timeline("p-1").getFirst();

        assertEquals("appeal.filed", read.type());
        assertEquals(1, read.payloadVersion());
        assertEquals(77, read.occurredAt());
        assertEquals(CaseEventOrigin.RECONSTRUCTED, read.origin());
        assertEquals(ActorTrust.UNVERIFIED, read.actorTrust());
        assertNull(read.actorOid());
        assertEquals(REVIEWER, read.recordedByOid());
        assertEquals(STAFF, read.actorSource());
        assertEquals("a-1", read.subjectId());
        assertEquals("STAFF", read.visibility(), "only staff-visible events are written");
        assertEquals(new CaseEventPayload.AppealFiled("a-1", true), read.payload());
        assertNull(read.redactedAt());
    }

    @Test
    void theSameDedupeKeyInsertsOnce() {
        givenPunishment("p-1", 1L, "{}");

        assertTrue(events.append(expired("p-1", "expired:p-1", 50)), "stored");
        assertFalse(events.append(expired("p-1", "expired:p-1", 50)), "a replay stores nothing");

        assertEquals(1, db.count("SELECT count(*) FROM case_event"));
    }

    @Test
    void eventsWithoutADedupeKeyMayRepeat() {
        givenPunishment("p-1", 1L, "{}");

        events.append(expired("p-1", null, 50));
        events.append(expired("p-1", null, 50));

        assertEquals(2, db.count("SELECT count(*) FROM case_event"));
    }

    @Test
    void anEventForAPunishmentThatDoesNotExistIsRefusedAndLeavesNoCase() {
        assertThrows(IllegalStateException.class, () -> events.append(expired("ghost", "expired:ghost", 50)));

        assertEquals(0, db.count("SELECT count(*) FROM punishment_case"), "the case insert rolled back with the statement");
    }

    @Test
    void recordingForAPunishmentThatDoesNotExistFailsQuietly() {
        timeline.record(expired("ghost", "expired:ghost", 50));

        assertEquals(0, db.count("SELECT count(*) FROM case_event"));
    }

    @Test
    void equalTimesAreOrderedBySeq() {
        givenPunishment("p-1", 1L, "{}");
        events.append(expired("p-1", "first", 10));
        events.append(expired("p-1", "second", 10));
        events.append(expired("p-1", "earlier", 5));

        assertEquals(List.of("earlier", "first", "second"), reader.timeline("p-1").stream().map(CaseEventRecord::dedupeKey).toList());
    }

    @Test
    void seqIncreasesWithEveryAppend() {
        givenPunishment("p-1", 1L, "{}");
        events.append(expired("p-1", "a", 10));
        events.append(expired("p-1", "b", 20));

        List<CaseEventRecord> read = reader.timeline("p-1");

        assertTrue(read.get(0).seq() < read.get(1).seq(), "seq is the total order");
    }

    @Test
    void deletingThePunishmentDeletesTheCaseAndItsEvents() {
        givenPunishment("p-1", 1L, "{}");
        events.append(expired("p-1", "a", 10));

        db.execute("DELETE FROM punishments WHERE identifier = 'p-1'");

        assertEquals(0, db.count("SELECT count(*) FROM punishment_case"));
        assertEquals(0, db.count("SELECT count(*) FROM case_event"));
    }

    @Test
    void deleteForPunishmentRemovesTheCaseAndOnlyItsEvents() {
        givenPunishment("p-1", 1L, "{}");
        givenPunishment("p-2", 1L, "{}");
        events.append(expired("p-1", "a", 10));
        events.append(expired("p-2", "b", 10));

        int deleted = events.deleteForPunishment("p-1");

        assertEquals(1, deleted);
        assertEquals(List.of("p-2"), db.column("SELECT punishment_identifier FROM case_event"));
        assertEquals(1, db.count("SELECT count(*) FROM punishment_case"));
    }

    @Test
    void redactingANoteRemovesEveryVersionOfItsTextAndKeepsTypeTimeAndActor() {
        givenPunishment("p-1", 1L, "{}");
        CaseActor author = new CaseActor(ActorTrust.VERIFIED, STAFF, STAFF, null);
        events.append(new CaseEventDraft("p-1", new CaseEventPayload.Unknown("note.added", 1, Map.of("noteId", "n-1", "text", "v1")), 10,
                CaseEventOrigin.LIVE, author, "n-1", null));
        events.append(new CaseEventDraft("p-1", new CaseEventPayload.Unknown("note.edited", 1, Map.of("noteId", "n-1", "text", "v2")), 20,
                CaseEventOrigin.LIVE, author, "n-1", null));
        events.append(new CaseEventDraft("p-1", new CaseEventPayload.Unknown("note.added", 1, Map.of("noteId", "n-2", "text", "other")), 30,
                CaseEventOrigin.LIVE, author, "n-2", null));
        events.append(expired("p-1", "expired:p-1", 40));

        int redacted = events.redact("p-1", "n-1", 99, REVIEWER);

        assertEquals(2, redacted, "the note and its edit");
        List<CaseEventRecord> read = reader.timeline("p-1");
        assertTrue(read.get(0).body().isEmpty() && read.get(1).body().isEmpty(), "the text is gone");
        assertEquals(99L, read.get(0).redactedAt());
        assertEquals(List.of("note.added", "note.edited", "note.added", "punishment.expired"), read.stream().map(CaseEventRecord::type).toList());
        assertEquals(STAFF, read.get(0).actorOid(), "the author stays");
        assertEquals("other", read.get(2).body().get("text"), "another note is untouched");
        assertFalse(read.get(3).body().isEmpty(), "other event types are untouched");
    }

    @Test
    void aClosedCaseIsReopenedWithOneSystemEventAndAHigherVersion() {
        givenPunishment("p-1", 1L, "{}");
        events.append(expired("p-1", "a", 10));
        db.execute("UPDATE punishment_case SET status = 'CLOSED'");
        clock.advance(Duration.ofMinutes(5));

        boolean reopened = new JdbcCaseWriter(db.dataSource).reopenIfClosed("p-1", clock.millis());

        assertTrue(reopened);
        assertEquals("OPEN", db.text("SELECT status FROM punishment_case"));
        assertEquals(1, db.count("SELECT version FROM punishment_case"));
        CaseEventRecord event = reader.timeline("p-1").stream().filter(e -> e.type().equals("case.status_changed")).findFirst().orElseThrow();
        assertEquals(new CaseEventPayload.StatusChanged("CLOSED", "OPEN"), event.payload());
        assertEquals(ActorTrust.SYSTEM, event.actorTrust());
        assertEquals(clock.millis(), event.occurredAt());
    }

    @Test
    void anOpenCaseIsNotReopenedAndGetsNoEvent() {
        givenPunishment("p-1", 1L, "{}");
        events.append(expired("p-1", "a", 10));

        boolean reopened = new JdbcCaseWriter(db.dataSource).reopenIfClosed("p-1", 5);

        assertFalse(reopened);
        assertEquals(1, db.count("SELECT count(*) FROM case_event"), "no status event");
        assertEquals(0, db.count("SELECT version FROM punishment_case"));
    }

    // ---- the reconciler against the real queries ------------------------------------------

    private CaseReconciler reconciler() {
        return new CaseReconciler(new JdbcCaseSourceReader(db.dataSource), timeline, clock, Duration.ofHours(48), Duration.ZERO);
    }

    @Test
    void theReconcilerRepairsAPunishmentThatNeverGotItsAppliedEventUsingTheRowsTime() {
        long createdAt = clock.millis() - Duration.ofHours(3).toMillis();
        givenPunishment("p-1", createdAt, "{}");

        int repaired = reconciler().reconcile();

        assertEquals(1, repaired);
        CaseEventRecord event = reader.timeline("p-1").getFirst();
        assertEquals("punishment.applied", event.type());
        assertEquals(CaseEventOrigin.RECONSTRUCTED, event.origin());
        assertEquals(createdAt, event.occurredAt(), "the creation time of the row, not the run time");
        assertEquals(STAFF, event.actorOid());
        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
    }

    @Test
    void aSecondReconcilerRunAddsNothing() {
        givenPunishment("p-1", clock.millis() - 1000, "{}");
        reconciler().reconcile();

        assertEquals(0, reconciler().reconcile());
        assertEquals(1, db.count("SELECT count(*) FROM case_event"));
    }

    @Test
    void theReconcilerLeavesAPunishmentWhoseEventWasRecordedLive() {
        givenPunishment("p-1", clock.millis() - 1000, "{}");
        events.append(new CaseEventDraft("p-1", new CaseEventPayload.Applied("network", 0, 1, 0, 1000, 1000, false, false, null, null),
                clock.millis(), CaseEventOrigin.LIVE, CaseActor.of(new net.onelitefeather.blackhole.backend.security.Attribution(STAFF, STAFF)),
                null, "applied:p-1"));

        assertEquals(0, reconciler().reconcile(), "nothing is lost");
        assertEquals(CaseEventOrigin.LIVE, reader.timeline("p-1").getFirst().origin());
    }

    @Test
    void theReconcilerIgnoresPunishmentsOlderThanTheLookBack() {
        givenPunishment("old", clock.millis() - Duration.ofDays(30).toMillis(), "{}");

        assertEquals(0, reconciler().reconcile(), "outside the look-back window");
    }

    @Test
    void theReconcilerRepairsALostExpiryAtTheExpiryTime() {
        long expiredAt = clock.millis() - Duration.ofHours(1).toMillis();
        givenPunishment("p-1", clock.millis() - Duration.ofHours(5).toMillis(), "{\"expiration_date\":%d}".formatted(expiredAt));
        events.append(CaseDrafts.applied(new SourceRows.PunishmentRow("p-1", STAFF, STAFF, null, "network", null, null, null, false, false,
                clock.millis() - Duration.ofHours(5).toMillis(), expiredAt, null, null, null, null, null), null, null, CaseEventOrigin.LIVE, 1));

        reconciler().reconcile();

        CaseEventRecord event = reader.timeline("p-1").stream().filter(e -> e.type().equals("punishment.expired")).findFirst().orElseThrow();
        assertEquals(expiredAt, event.occurredAt());
        assertEquals(ActorTrust.SYSTEM, event.actorTrust());
        assertEquals(CaseEventOrigin.RECONSTRUCTED, event.origin());
    }

    @Test
    void theReconcilerRepairsALostAppealFilingAndDecision() {
        long created = clock.millis() - Duration.ofHours(10).toMillis();
        givenPunishment("p-1", created, "{}");
        UUID appeal = UUID.randomUUID();
        db.execute("INSERT INTO appeals (identifier, appellant_hash, created_at, decided_by, recorded_by_oid, status, updated_at, punishment_identifier) "
                + "VALUES (?, ?, ?, ?, ?, 5, ?, 'p-1')", appeal, OWNER, created + 100, REVIEWER, REVIEWER, created + 200);

        reconciler().reconcile();

        List<CaseEventRecord> read = reader.timeline("p-1");
        assertEquals(List.of("punishment.applied", "appeal.filed", "appeal.decided"), read.stream().map(CaseEventRecord::type).toList());
        assertEquals(created + 100, read.get(1).occurredAt(), "when the appeal was filed");
        assertEquals(created + 200, read.get(2).occurredAt(), "when it was decided");
        assertEquals(REVIEWER, read.get(2).actorOid());
    }

    @Test
    void theReconcilerRepairsLostEvidenceEventsFromTheItemsStatus() {
        long created = clock.millis() - Duration.ofHours(10).toMillis();
        givenPunishment("p-1", created, "{}");
        UUID evidence = UUID.randomUUID();
        db.execute("INSERT INTO evidence_items (identifier, punishment_identifier, owner, kind, source_system, created_at, created_by, "
                + "recorded_by_oid, status, status_changed_at) VALUES (?, 'p-1', ?, 3, 'grim', ?, ?, ?, 1, ?)",
                evidence, OWNER, created + 10, STAFF, STAFF, created + 500);

        reconciler().reconcile();

        List<CaseEventRecord> read = reader.timeline("p-1");
        assertEquals(List.of("punishment.applied", "evidence.attached", "evidence.revoked"), read.stream().map(CaseEventRecord::type).toList());
        assertEquals(created + 500, read.get(2).occurredAt(), "the status change time of the item");
        assertEquals(new CaseEventPayload.EvidenceRevoked(evidence.toString(), false), read.get(2).payload());
    }
}
