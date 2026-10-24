package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.JdbcProtocolSource;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.JdbcProtocolStore;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolStore;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The protocol index, the purge marking of the redaction statements and the candidate query against a real PostgreSQL schema (V17). */
@Tag("integration")
class ProtocolJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "proto-1";
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID ADMIN = UUID.nameUUIDFromBytes("admin".getBytes());
    private static final CaseActor STAFF_ACTOR = new CaseActor(ActorTrust.VERIFIED, STAFF, STAFF, null);
    private static final UUID APPEAL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final int DENIED = AppealStatus.DENIED.ordinal();

    private CaseDatabase db;
    private JdbcCaseWriter writer;
    private JdbcCaseEventRepository events;
    private JdbcProtocolStore store;

    @BeforeEach
    void givenAMigratedSchemaWithAPunishmentAndADecidedAppeal() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        writer = new JdbcCaseWriter(db.dataSource);
        events = new JdbcCaseEventRepository(db.dataSource);
        store = new JdbcProtocolStore(db.dataSource);
        givenPunishment(ID, "AAAAAAA1");
        writer.ensureCase(ID, 10L);
        givenAppeal(APPEAL, ID, DENIED, 100L, 200L);
    }

    private void givenPunishment(String id, String shortId) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data, "
                + "ban_id_hash, ban_id_enc) VALUES (?, ?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb, ?, ?)",
                id, STAFF, STAFF, OWNER, shortId, id.getBytes(), new byte[]{1});
    }

    private void givenAppeal(UUID id, String punishment, int status, long createdAt, long updatedAt) {
        db.execute("INSERT INTO appeals (identifier, appellant_hash, created_at, status, updated_at, punishment_identifier) VALUES (?, ?, ?, ?, ?, ?)",
                id, OWNER, createdAt, status, updatedAt, punishment);
    }

    private UUID givenTranscriptEvidence(String punishment) {
        UUID id = UUID.randomUUID();
        db.execute("INSERT INTO evidence_items (identifier, punishment_identifier, owner, kind, source_system, created_at, file_key, json_file_key, "
                + "json_file_size, json_file_sha256) VALUES (?, ?, ?, 5, 'blackhole', 1, ?, ?, 2, 'ab')", id, punishment, OWNER, "k/" + id, "j/" + id);
        return id;
    }

    private ProtocolStore.NewVersion version(int number, String digest) {
        return new ProtocolStore.NewVersion(UUID.randomUUID(), ID, APPEAL, number, givenTranscriptEvidence(ID), digest, 3, 1L, 9L, 1000L + number);
    }

    private List<String> statuses() {
        return db.column("SELECT status FROM appeal_protocol WHERE appeal_id = ? ORDER BY version", APPEAL).stream().map(String.class::cast).toList();
    }

    private String playerMessage(String text, long at) {
        String messageId = UUID.randomUUID().toString();
        writer.appendMessage(CaseDrafts.playerMessage(ID, messageId, APPEAL.toString(), text, at), true);
        return messageId;
    }

    // ---- the index ----

    @Test
    void theFirstVersionIsActive() {
        assertTrue(store.add(version(1, "d1")));

        ProtocolVersion active = store.active(APPEAL).orElseThrow();
        assertEquals(1, active.version());
        assertEquals(ProtocolVersion.Status.ACTIVE, active.status());
        assertEquals(2, store.nextVersion(APPEAL));
    }

    @Test
    void aNewVersionSupersedesThePreviousActiveOneInTheSameStatement() {
        store.add(version(1, "d1"));

        assertTrue(store.add(version(2, "d2")));

        assertEquals(List.of("SUPERSEDED", "ACTIVE"), statuses());
        assertEquals(2, store.active(APPEAL).orElseThrow().version());
    }

    @Test
    void aReadableVersionWithTheSameDigestIsNotAddedAgainAndNothingIsSuperseded() {
        store.add(version(1, "d1"));

        assertFalse(store.add(version(2, "d1")), "idempotent per digest");

        assertEquals(List.of("ACTIVE"), statuses());
    }

    @Test
    void aVersionNumberCannotBeTakenTwice() {
        store.add(version(1, "d1"));

        assertFalse(store.add(version(1, "d2")));

        assertEquals(List.of("ACTIVE"), statuses());
    }

    @Test
    void twoConcurrentGenerationsOfTheSameStateStoreExactlyOneVersion() throws Exception {
        ProtocolStore.NewVersion a = version(1, "same");
        ProtocolStore.NewVersion b = version(1, "same");

        List<Boolean> results = runTogether(() -> store.add(a), () -> store.add(b));

        assertEquals(1, results.stream().filter(added -> added).count(), "exactly one wins: " + results);
        assertEquals(List.of("ACTIVE"), statuses());
    }

    @Test
    void twoConcurrentNewVersionsOnAnActiveOneLeaveExactlyOneActive() throws Exception {
        store.add(version(1, "d1"));
        ProtocolStore.NewVersion a = version(2, "same");
        ProtocolStore.NewVersion b = version(2, "same");

        List<Boolean> results = runTogether(() -> store.add(a), () -> store.add(b));

        assertEquals(1, results.stream().filter(added -> added).count(), results.toString());
        assertEquals(List.of("SUPERSEDED", "ACTIVE"), statuses());
    }

    private static List<Boolean> runTogether(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    ready.await();
                    return task.call();
                }));
            }
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aPurgedVersionKeepsItsNumberAndDoesNotBlockTheSameDigest() {
        store.add(version(1, "d1"));
        assertEquals(1, store.markPurgePending(ID));
        assertTrue(store.active(APPEAL).isEmpty(), "unreadable at once");
        ProtocolVersion pending = store.purgePending(10).getFirst();
        assertTrue(store.markPurged(pending.identifier(), 5L));

        assertEquals(2, store.nextVersion(APPEAL), "numbers are never reused");
        assertTrue(store.add(version(2, "d1")), "the regenerated version may have the same digest");
        assertEquals(List.of("PURGED", "ACTIVE"), statuses());
    }

    @Test
    void deletingTheEvidenceLeavesATombstoneAndTheRowIsSettledAsPurged() {
        store.add(version(1, "d1"));
        UUID evidence = store.active(APPEAL).orElseThrow().evidenceId();

        db.execute("DELETE FROM evidence_items WHERE identifier = ?", evidence);

        assertTrue(store.active(APPEAL).orElseThrow().evidenceId() == null, "set null, not cascade");
        assertEquals(1, store.settleLostEvidence(APPEAL, 7L));
        assertTrue(store.active(APPEAL).isEmpty());
        assertEquals(List.of("PURGED"), statuses());
    }

    @Test
    void aTranscriptIsReadableOnlyWhileItsVersionIs() {
        store.add(version(1, "d1"));
        UUID evidence = store.active(APPEAL).orElseThrow().evidenceId();
        assertTrue(store.readable(evidence));

        store.markPurgePending(ID);

        assertFalse(store.readable(evidence));
        assertFalse(store.readable(UUID.randomUUID()), "an item without an index row is not served");
    }

    @Test
    void deletingThePunishmentRemovesItsProtocolRows() {
        store.add(version(1, "d1"));
        db.execute("DELETE FROM evidence_items WHERE punishment_identifier = ?", ID);
        db.execute("DELETE FROM appeals WHERE punishment_identifier = ?", ID);

        db.execute("DELETE FROM punishments WHERE identifier = ?", ID);

        assertEquals(0, db.count("SELECT count(*) FROM appeal_protocol"));
    }

    // ---- redaction marks in the same statement ----

    @Test
    void redactingAMessageMarksEveryReadableVersionInTheSameStatement() {
        String messageId = playerMessage("secret", 150L);
        store.add(version(1, "d1"));
        store.add(version(2, "d2"));

        int redacted = events.redact(ID, messageId, 300L, ADMIN);

        assertEquals(1, redacted);
        assertEquals(List.of("PURGE_PENDING", "PURGE_PENDING"), statuses());
    }

    @Test
    void redactingANoteDoesNotTouchTheProtocol() {
        events.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded("note-1", "internal"), 120L, CaseEventOrigin.LIVE, STAFF_ACTOR,
                "note-1", "note.added:note-1"));
        store.add(version(1, "d1"));

        events.redact(ID, "note-1", 300L, ADMIN);

        assertEquals(List.of("ACTIVE"), statuses(), "notes are not in the protocol");
    }

    @Test
    void redactingNothingMarksNothing() {
        store.add(version(1, "d1"));

        assertEquals(0, events.redact(ID, "no-such-message", 300L, ADMIN));

        assertEquals(List.of("ACTIVE"), statuses());
    }

    @Test
    void erasingThePlayerSideMarksTheVersionsInTheSameStatement() {
        playerMessage("my address", 150L);
        store.add(version(1, "d1"));

        List<CaseChanges.Erased> erased = writer.erasePlayerMessages(ID, "OTHER", 300L, ADMIN, STAFF_ACTOR);

        assertEquals(1, erased.size());
        assertEquals(List.of("PURGE_PENDING"), statuses());
    }

    @Test
    void erasingWhenThePlayerWroteNothingMarksNothing() {
        store.add(version(1, "d1"));

        assertTrue(writer.erasePlayerMessages(ID, "OTHER", 300L, ADMIN, STAFF_ACTOR).isEmpty());

        assertEquals(List.of("ACTIVE"), statuses());
    }

    @Test
    void anonymisingAStaffActorMarksTheProtocolsOfTheCasesTheyActedIn() {
        events.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded("note-1", "x"), 120L, CaseEventOrigin.LIVE, STAFF_ACTOR,
                "note-1", "note.added:note-1"));
        store.add(version(1, "d1"));

        assertEquals(List.of(ID), store.markPurgePendingForActor(STAFF));
        assertEquals(List.of("PURGE_PENDING"), statuses());
        assertTrue(store.markPurgePendingForActor(UUID.randomUUID()).isEmpty());
    }

    // ---- reconciler candidates ----

    @Test
    void aDecidedAppealWithoutAProtocolIsACandidateAndOneUpToDateIsNot() {
        assertEquals(List.of(APPEAL), store.candidates(10));

        store.add(new ProtocolStore.NewVersion(UUID.randomUUID(), ID, APPEAL, 1, givenTranscriptEvidence(ID), "d1", 1, 1L, lastSeq(), 1L));
        assertTrue(store.candidates(10).isEmpty(), "current: nothing newer than the active version");
    }

    @Test
    void aMessageAfterTheActiveVersionMakesTheAppealACandidate() {
        store.add(new ProtocolStore.NewVersion(UUID.randomUUID(), ID, APPEAL, 1, givenTranscriptEvidence(ID), "d1", 1, 1L, lastSeq(), 1L));

        playerMessage("late", 400L);

        assertEquals(List.of(APPEAL), store.candidates(10));
    }

    @Test
    void anUndecidedAppealIsNeverACandidate() {
        db.execute("UPDATE appeals SET status = ? WHERE identifier = ?", AppealStatus.IN_REVIEW.ordinal(), APPEAL);

        assertTrue(store.candidates(10).isEmpty());
    }

    @Test
    void aPendingPurgeMakesTheAppealACandidate() {
        store.add(new ProtocolStore.NewVersion(UUID.randomUUID(), ID, APPEAL, 1, givenTranscriptEvidence(ID), "d1", 1, 1L, lastSeq(), 1L));
        store.markPurgePending(ID);

        assertEquals(List.of(APPEAL), store.candidates(10));
    }

    private long lastSeq() {
        return ((Number) db.column("SELECT COALESCE(MAX(seq), 0) FROM case_event WHERE punishment_identifier = ?", ID).getFirst()).longValue();
    }

    // ---- the source ----

    @Test
    void theSourceReadsTheAppealAndTheCaseEventsOnly() {
        playerMessage("hello", 150L);
        JdbcProtocolSource source = new JdbcProtocolSource(db.dataSource, new JdbcCaseReader(db.dataSource));

        var appeal = source.appeal(APPEAL).orElseThrow();

        assertEquals(ID, appeal.punishmentIdentifier());
        assertEquals(OWNER, appeal.owner());
        assertTrue(appeal.decided());
        assertEquals(List.of(APPEAL), source.appealsOf(ID).stream().map(a -> a.identifier()).toList());
        assertEquals(1, source.entries(ID).stream().filter(e -> e.event().type().equals("message.added")).count());
    }
}
