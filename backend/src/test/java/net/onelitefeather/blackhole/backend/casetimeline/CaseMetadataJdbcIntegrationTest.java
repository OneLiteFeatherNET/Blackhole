package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The case writes and reads against a real PostgreSQL schema: atomicity, optimistic versions, paging and filters. */
@Tag("integration")
class CaseMetadataJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "case-1";
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final CaseActor ACTOR = new CaseActor(ActorTrust.VERIFIED, STAFF, STAFF, null);

    private CaseDatabase db;
    private JdbcCaseWriter writer;
    private JdbcCaseReader reader;
    private JdbcCaseEventRepository events;

    @BeforeEach
    void givenAMigratedSchema() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        writer = new JdbcCaseWriter(db.dataSource);
        reader = new JdbcCaseReader(db.dataSource);
        events = new JdbcCaseEventRepository(db.dataSource);
    }

    private void givenPunishment(String id, String shortId, long createdAt, boolean lifted, String section, String metaData) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, ?, ?, false, ?, ?, ?, ?::jsonb)", id, STAFF, STAFF, OWNER, section, lifted, createdAt, shortId, metaData);
    }

    private void givenCase() {
        givenPunishment(ID, "AAAAAAA1", 1L, false, "network", "{}");
        writer.ensureCase(ID, 10L);
    }

    private static CaseMeta withPriority(String priority) {
        return new CaseMeta("OPEN", null, priority, List.of());
    }

    @Test
    void anUpdateChangesTheStateAndAppendsItsEventTogether() {
        givenCase();

        List<CaseChanges.Appended> appended = writer.update(ID, 0, 20L, ACTOR, CaseMeta.defaults(),
                new CaseMeta("IN_REVIEW", STAFF, "HIGH", List.of("a", "b")));

        assertEquals(Set.of("case.status_changed", "case.assignee_changed", "case.priority_changed", "case.tags_changed"),
                appended.stream().map(CaseChanges.Appended::type).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, appended.size(), "one event per changed field");
        CaseReader.CaseRow row = reader.find(ID).orElseThrow();
        assertEquals("IN_REVIEW", row.meta().status());
        assertEquals(STAFF, row.meta().assignee());
        assertEquals("HIGH", row.meta().priority());
        assertEquals(List.of("a", "b"), row.meta().tags());
        assertEquals(1, row.version(), "the version moved once");
        assertEquals(4, db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ?", ID));
    }

    @Test
    void aStaleVersionChangesNeitherTheStateNorTheLog() {
        givenCase();
        writer.update(ID, 0, 20L, ACTOR, CaseMeta.defaults(), withPriority("HIGH"));

        List<CaseChanges.Appended> stale = writer.update(ID, 0, 30L, ACTOR, withPriority("HIGH"), withPriority("URGENT"));

        assertTrue(stale.isEmpty(), "the statement reports that nothing happened");
        assertEquals("HIGH", reader.find(ID).orElseThrow().meta().priority(), "state unchanged");
        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ?", ID), "no event appended");
    }

    @Test
    void theAppendedEventRecordsTheActorTheTimeAndTheChange() {
        givenCase();

        writer.update(ID, 0, 20L, ACTOR, CaseMeta.defaults(), withPriority("LOW"));

        CaseEventRecord event = new JdbcCaseEventReader(db.dataSource).timeline(ID).getFirst();
        assertEquals(new CaseEventPayload.PriorityChanged("NORMAL", "LOW"), event.payload());
        assertEquals(20L, event.occurredAt(), "the injected time, not the database clock");
        assertEquals(STAFF, event.actorOid());
        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals("STAFF", event.visibility());
    }

    @Test
    void concurrentWritersOfTheSameVersionLetExactlyOneWin() throws Exception {
        givenCase();
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String priority = i % 2 == 0 ? "HIGH" : "URGENT";
                results.add(pool.submit(() -> {
                    start.await();
                    return !writer.update(ID, 0, 20L, ACTOR, CaseMeta.defaults(), withPriority(priority)).isEmpty();
                }));
            }
            start.countDown();
            int wins = 0;
            for (Future<Boolean> result : results) {
                wins += result.get() ? 1 : 0;
            }

            assertEquals(1, wins, "one writer wins, the others see a stale version");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, reader.find(ID).orElseThrow().version());
        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ?", ID), "one event for the one change");
    }

    @Test
    void writersThatRetryOnAStaleVersionLoseNoUpdate() throws Exception {
        givenCase();
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String tag = "tag-" + i;
                Callable<Integer> task = () -> {
                    start.await();
                    int attempts = 0;
                    while (true) {
                        attempts++;
                        CaseReader.CaseRow row = reader.find(ID).orElseThrow();
                        List<String> tags = new ArrayList<>(row.meta().tags());
                        tags.add(tag);
                        tags.sort(String::compareTo);
                        CaseMeta after = new CaseMeta(row.meta().status(), row.meta().assignee(), row.meta().priority(), tags);
                        if (!writer.update(ID, row.version(), 20L, ACTOR, row.meta(), after).isEmpty()) {
                            return attempts;
                        }
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertNotNull(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        CaseReader.CaseRow row = reader.find(ID).orElseThrow();
        assertEquals(IntStream.range(0, writers).mapToObj(i -> "tag-" + i).sorted().toList(), row.meta().tags(), "every writer's tag survived");
        assertEquals(writers, row.version(), "one version per applied change");
        assertEquals(writers, db.count("SELECT count(*) FROM case_event WHERE type = 'case.tags_changed'"), "one event per applied change");
    }

    @Test
    void ensuringACaseTwiceKeepsTheFirstRow() {
        givenCase();
        writer.update(ID, 0, 20L, ACTOR, CaseMeta.defaults(), withPriority("HIGH"));

        writer.ensureCase(ID, 99L);

        assertEquals(1, db.count("SELECT count(*) FROM punishment_case"));
        assertEquals("HIGH", reader.find(ID).orElseThrow().meta().priority(), "an existing case is not reset");
    }

    @Test
    void aTagThatIsNoSlugNeverReachesTheDatabase() {
        givenCase();

        try {
            writer.update(ID, 0, 20L, ACTOR, CaseMeta.defaults(), new CaseMeta("OPEN", null, "NORMAL", List.of("x\"],\"y")));
            throw new AssertionError("expected the writer to refuse the tag");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, reader.find(ID).orElseThrow().version(), "nothing changed");
        }
    }

    @Test
    void editingANoteAfterItsRedactionAppendsNothing() {
        givenCase();
        String note = UUID.randomUUID().toString();
        events.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded(note, "text"), 20L, CaseEventOrigin.LIVE, ACTOR, note, null));
        events.redact(ID, note, 30L, STAFF);

        CaseChanges.Appended edited = writer.editNote(ID, note, "back again", 40L, ACTOR);

        assertNull(edited, "a redacted note stays redacted");
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE type = 'note.edited'"), "no edit row");
    }

    @Test
    void editingALiveNoteAppendsTheNewVersion() {
        givenCase();
        String note = UUID.randomUUID().toString();
        events.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded(note, "text"), 20L, CaseEventOrigin.LIVE, ACTOR, note, null));

        CaseChanges.Appended edited = writer.editNote(ID, note, "better text", 40L, ACTOR);

        assertNotNull(edited);
        assertEquals(List.of("note.added", "note.edited"), reader.subject(ID, note).stream().map(CaseEventRecord::type).toList());
        assertEquals(new CaseEventPayload.NoteEdited(note, "better text"), reader.subject(ID, note).getLast().payload());
    }

    @Test
    void redactionNullsEveryVersionAndKeepsTypeTimeAndActor() {
        givenCase();
        String note = UUID.randomUUID().toString();
        events.append(new CaseEventDraft(ID, new CaseEventPayload.NoteAdded(note, "secret"), 20L, CaseEventOrigin.LIVE, ACTOR, note, null));
        writer.editNote(ID, note, "secret 2", 30L, ACTOR);

        int redacted = events.redact(ID, note, 50L, STAFF);

        assertEquals(2, redacted);
        assertEquals(2, db.count("SELECT count(*) FROM case_event WHERE subject_id = ? AND payload IS NULL AND redacted_at = 50 AND redacted_by_oid = ?", note, STAFF));
        assertEquals(List.of(20L, 30L), reader.subject(ID, note).stream().map(CaseEventRecord::occurredAt).toList(), "time stays");
        assertTrue(reader.subject(ID, note).stream().allMatch(row -> STAFF.equals(row.actorOid())), "actor stays");
        assertEquals(0, events.redact(ID, note, 60L, STAFF), "redacting again changes nothing, the first redaction time stays");
        assertEquals(50L, reader.subject(ID, note).getFirst().redactedAt());
    }

    private void givenEvents(int count) {
        givenCase();
        for (int i = 0; i < count; i++) {
            String type = i % 3 == 0 ? CaseEventPayload.APPEAL_FILED : CaseEventPayload.EXPIRED;
            CaseEventPayload payload = i % 3 == 0 ? new CaseEventPayload.AppealFiled("a-" + i, true) : new CaseEventPayload.Expired(i);
            CaseActor actor = i % 5 == 0 ? CaseActor.system() : ACTOR;
            // Every event shares one of ten times, so the order inside a time depends on seq alone.
            events.append(new CaseEventDraft(ID, payload, 100L + (i % 10), CaseEventOrigin.LIVE, actor, null, type + ":" + i));
        }
    }

    private List<CaseReader.Entry> page(String after, boolean ascending, int limit, List<String> types, ActorTrust trust, Long from, Long to) {
        Long occurredAt = after == null ? null : Long.parseLong(after.split(":")[0]);
        Long seq = after == null ? null : Long.parseLong(after.split(":")[1]);
        return reader.timeline(new CaseReader.TimelineQuery(ID, occurredAt, seq, types, trust, from, to, ascending, limit));
    }

    @Test
    void followingTheCursorPagesThroughEveryEventOnceInTimelineOrder() {
        givenEvents(120);
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        List<Integer> sizes = new ArrayList<>();

        while (true) {
            List<CaseReader.Entry> entries = page(cursor, true, 50, List.of(), null, null, null);
            sizes.add(entries.size());
            entries.forEach(entry -> seen.add(entry.event().seq()));
            if (entries.size() < 50) {
                break;
            }
            CaseEventRecord last = entries.getLast().event();
            cursor = last.occurredAt() + ":" + last.seq();
        }

        assertEquals(List.of(50, 50, 20), sizes);
        assertEquals(120, new HashSet<>(seen).size(), "no overlap and no gap");
        List<CaseEventRecord> all = new JdbcCaseEventReader(db.dataSource).timeline(ID);
        assertEquals(all.stream().map(CaseEventRecord::seq).toList(), seen, "the pages follow the timeline order (time, then seq)");
    }

    @Test
    void theNewestFirstOrderMirrorsTheOldestFirstOrder() {
        givenEvents(30);

        List<Long> newest = page(null, false, 100, List.of(), null, null, null).stream().map(entry -> entry.event().seq()).toList();
        List<Long> oldest = new ArrayList<>(page(null, true, 100, List.of(), null, null, null).stream().map(entry -> entry.event().seq()).toList());
        java.util.Collections.reverse(oldest);

        assertEquals(oldest, newest);
    }

    @Test
    void aTypeGroupFilterReturnsOnlyThatGroup() {
        givenEvents(30);

        List<CaseReader.Entry> appeals = page(null, true, 100, List.of("appeal"), null, null, null);

        assertEquals(10, appeals.size());
        assertTrue(appeals.stream().allMatch(entry -> entry.event().type().startsWith("appeal.")), "only appeal events");
    }

    @Test
    void anExactTypeFilterDoesNotMatchALongerType() {
        givenEvents(30);

        assertEquals(20, page(null, true, 100, List.of("punishment.expired"), null, null, null).size());
        assertEquals(0, page(null, true, 100, List.of("punishment.expire"), null, null, null).size(), "no prefix match without the dot");
    }

    @Test
    void theActorTrustAndTimeFiltersNarrowTheResult() {
        givenEvents(30);

        assertEquals(6, page(null, true, 100, List.of(), ActorTrust.SYSTEM, null, null).size(), "every fifth event is the system's");
        assertEquals(6, page(null, true, 100, List.of(), null, 100L, 101L).size(), "two of ten times, three events each");
        assertEquals(3, page(null, true, 100, List.of(), null, 109L, null).size(), "from is inclusive");
    }

    @Test
    void theActorNameIsJoinedFromTheStaffActorAndNullAfterAnonymizing() {
        givenCase();
        db.execute("INSERT INTO staff_actor (oid, kind, display_name, first_seen_at, last_seen_at) VALUES (?, 'HUMAN', 'Alice', 1, 1)", STAFF);
        events.append(new CaseEventDraft(ID, new CaseEventPayload.Expired(1), 5L, CaseEventOrigin.LIVE, ACTOR, null, "e1"));

        assertEquals("Alice", page(null, true, 10, List.of(), null, null, null).getFirst().actorName());
        db.execute("UPDATE staff_actor SET display_name = NULL WHERE oid = ?", STAFF);

        CaseReader.Entry anonymized = page(null, true, 10, List.of(), null, null, null).getFirst();
        assertNull(anonymized.actorName(), "an anonymized actor has no name");
        assertEquals(STAFF, anonymized.event().actorOid(), "the oid stays");
    }

    @Test
    void relatedListsTheOtherPunishmentsOfTheSameOwnerNewestFirstWithTheirState() {
        givenPunishment("p-old", "00000001", 10L, false, "chat", "{\"expiration_date\": 20}");
        givenPunishment("p-lifted", "00000002", 20L, true, "network", "{}");
        givenPunishment("p-active", "00000003", 30L, false, "network", "{}");
        givenPunishment("p-this", "00000004", 40L, false, "network", "{}");
        writer.ensureCase("p-active", 1L);
        writer.update("p-active", 0, 2L, ACTOR, CaseMeta.defaults(), new CaseMeta("CLOSED", null, "NORMAL", List.of()));

        List<CaseReader.Related> related = reader.related(OWNER, "p-this", 1_000L, 20);

        assertEquals(List.of("p-active", "p-lifted", "p-old"), related.stream().map(CaseReader.Related::identifier).toList());
        assertEquals(List.of("ACTIVE", "LIFTED", "EXPIRED"), related.stream().map(CaseReader.Related::state).toList());
        assertEquals(List.of("CLOSED", "OPEN", "OPEN"), related.stream().map(CaseReader.Related::caseStatus).toList(), "no case row reads as OPEN");
        assertEquals("#00000003", related.getFirst().shortId());
    }

    @Test
    void theListFiltersByStatusAssigneeTagPriorityAndPunishmentState() {
        givenPunishment("c-1", "00000011", 10L, false, "network", "{}");
        givenPunishment("c-2", "00000012", 20L, true, "chat", "{}");
        givenPunishment("c-3", "00000013", 30L, false, "network", "{\"expiration_date\": 5}");
        for (String id : List.of("c-1", "c-2", "c-3")) {
            writer.ensureCase(id, 1L);
        }
        writer.update("c-1", 0, 2L, ACTOR, CaseMeta.defaults(), new CaseMeta("IN_REVIEW", STAFF, "HIGH", List.of("evasion")));
        writer.update("c-2", 0, 3L, ACTOR, CaseMeta.defaults(), new CaseMeta("OPEN", null, "NORMAL", List.of("evasion", "x")));

        assertEquals(List.of("c-1"), ids(new CaseReader.ListQuery("IN_REVIEW", null, null, null, null, null, 0, 20)));
        assertEquals(List.of("c-1"), ids(new CaseReader.ListQuery(null, STAFF, null, null, null, null, 0, 20)));
        assertEquals(List.of("c-1"), ids(new CaseReader.ListQuery(null, null, null, "HIGH", null, null, 0, 20)));
        assertEquals(Set.of("c-1", "c-2"), new HashSet<>(ids(new CaseReader.ListQuery(null, null, "evasion", null, null, null, 0, 20))));
        assertEquals(List.of("c-2"), ids(new CaseReader.ListQuery(null, null, null, null, "chat", null, 0, 20)));
        assertEquals(List.of("c-2"), ids(new CaseReader.ListQuery(null, null, null, null, null, "LIFTED", 0, 20)));
        assertEquals(List.of("c-3"), ids(new CaseReader.ListQuery(null, null, null, null, null, "EXPIRED", 0, 20)));
        assertEquals(List.of("c-1"), ids(new CaseReader.ListQuery(null, null, null, null, null, "ACTIVE", 0, 20)));
    }

    @Test
    void theListIsOrderedByTheLatestEventAndPaged() {
        givenPunishment("c-1", "00000011", 10L, false, "network", "{}");
        givenPunishment("c-2", "00000012", 20L, false, "network", "{}");
        givenPunishment("c-3", "00000013", 30L, false, "network", "{}");
        events.append(new CaseEventDraft("c-1", new CaseEventPayload.Expired(1), 300L, CaseEventOrigin.LIVE, ACTOR, null, "k1"));
        events.append(new CaseEventDraft("c-2", new CaseEventPayload.Expired(1), 100L, CaseEventOrigin.LIVE, ACTOR, null, "k2"));
        events.append(new CaseEventDraft("c-3", new CaseEventPayload.Expired(1), 200L, CaseEventOrigin.LIVE, ACTOR, null, "k3"));

        CaseReader.ListPage first = reader.list(new CaseReader.ListQuery(null, null, null, null, null, null, 0, 2), 1_000L);
        CaseReader.ListPage second = reader.list(new CaseReader.ListQuery(null, null, null, null, null, null, 1, 2), 1_000L);

        assertEquals(List.of("c-1", "c-3"), first.items().stream().map(CaseReader.Listed::identifier).toList(), "latest activity first");
        assertEquals(List.of("c-2"), second.items().stream().map(CaseReader.Listed::identifier).toList());
        assertEquals(3, first.total());
    }

    private List<String> ids(CaseReader.ListQuery query) {
        return reader.list(query, 1_000L).items().stream().map(CaseReader.Listed::identifier).toList();
    }
}
