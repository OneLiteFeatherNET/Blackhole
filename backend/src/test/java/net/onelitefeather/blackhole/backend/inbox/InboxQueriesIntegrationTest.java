package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import net.onelitefeather.blackhole.backend.report.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four inbox sections against the real schema, migrated by the real Flyway migrations. Each test
 * builds its own schema, so no row of one test is visible to another.
 */
@Tag("integration")
class InboxQueriesIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "cd".repeat(64);
    private static final String STAFF = UUID.nameUUIDFromBytes("inbox-staff".getBytes()).toString();

    private CaseDatabase db;
    private InboxQueries queries;
    private int sequence;

    @BeforeEach
    void givenAMigratedSchema() {
        this.db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        this.queries = new InboxQueries(this.db.dataSource);
    }

    private static String id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes()).toString();
    }

    private void punishment(String identifier, String caseStatus, String assigneeOid, long createdAt) {
        this.sequence++;
        this.db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, "
                + "created_at, short_id, meta_data) VALUES (?, ?::uuid, ?::uuid, ?, 'network', false, false, ?, ?, '{}'::jsonb)",
                identifier, STAFF, STAFF, OWNER, createdAt, String.format("%08X", this.sequence));
        this.db.execute("INSERT INTO punishment_case (punishment_identifier, status, assignee_oid, priority, tags, version, "
                + "created_at, updated_at) VALUES (?, ?, ?::uuid, 'NORMAL', '[]'::jsonb, 0, ?, ?)",
                identifier, caseStatus, assigneeOid, createdAt, createdAt);
    }

    private void appeal(String identifier, AppealStatus status, String punishmentIdentifier, long createdAt) {
        this.db.execute("INSERT INTO appeals (identifier, appellant_hash, created_at, status, updated_at, punishment_identifier) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?)", identifier, OWNER, createdAt, (short) status.ordinal(), createdAt,
                punishmentIdentifier);
    }

    private void report(String identifier, ReportStatus status, long createdAt) {
        this.db.execute("INSERT INTO reports (identifier, created_at, reporter_hash, reported_hash, status, updated_at) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?)", identifier, createdAt, OWNER, OWNER, (short) status.ordinal(), createdAt);
    }

    /** A chat message of an appeal, as the case log records it; {@code deleted} adds the deletion that hides it. */
    private void message(String punishmentIdentifier, String appealId, String messageId, String authorKind, String visibility,
                         long occurredAt, boolean deleted) {
        this.db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, "
                        + "actor_trust, visibility, payload, subject_id, dedupe_key) "
                        + "VALUES (?::uuid, ?, 'message.added', 2, ?, 'LIVE', 'PLAYER', ?, ?::jsonb, ?, ?)",
                id(messageId + "-added"), punishmentIdentifier, occurredAt, visibility,
                "{\"messageId\":\"" + messageId + "\",\"appealId\":\"" + appealId + "\",\"author\":{\"kind\":\"" + authorKind + "\"}}",
                messageId, "message:" + messageId);
        if (deleted) {
            this.db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, "
                            + "actor_trust, visibility, subject_id, dedupe_key) "
                            + "VALUES (?::uuid, ?, 'message.deleted', 1, ?, 'LIVE', 'PLAYER', ?, ?, ?)",
                    id(messageId + "-deleted"), punishmentIdentifier, occurredAt + 1, visibility, messageId,
                    "message.deleted:" + messageId);
        }
    }

    private List<String> ids(List<InboxDTO.Item> items) {
        return items.stream().map(InboxDTO.Item::id).toList();
    }

    @Test
    void needsAnAnswerListsAnAppealWhoseLastVisibleMessageIsFromThePlayer() {
        String appeal = id("appeal-1");
        punishment("p-1", "OPEN", null, 10);
        appeal(appeal, AppealStatus.SUBMITTED, "p-1", 10);
        message("p-1", appeal, id("m-1"), "PLAYER", "PLAYER", 20, false);

        assertEquals(List.of(appeal), ids(this.queries.oldest(InboxSection.NEEDS_ANSWER, 10)));
        assertEquals(1, this.queries.count(InboxSection.NEEDS_ANSWER));
    }

    @Test
    void needsAnAnswerExcludesAnAppealWhoseLastVisibleMessageIsStaffs() {
        String appeal = id("appeal-1");
        punishment("p-1", "OPEN", null, 10);
        appeal(appeal, AppealStatus.IN_REVIEW, "p-1", 10);
        message("p-1", appeal, id("m-1"), "PLAYER", "PLAYER", 20, false);
        message("p-1", appeal, id("m-2"), "STAFF", "PLAYER", 30, false);

        assertEquals(List.of(), ids(this.queries.oldest(InboxSection.NEEDS_ANSWER, 10)));
        assertEquals(0, this.queries.count(InboxSection.NEEDS_ANSWER));
    }

    @Test
    void needsAnAnswerIgnoresAPlayerMessageThatWasDeleted() {
        String appeal = id("appeal-1");
        punishment("p-1", "OPEN", null, 10);
        appeal(appeal, AppealStatus.SUBMITTED, "p-1", 10);
        message("p-1", appeal, id("m-1"), "STAFF", "PLAYER", 20, false);
        message("p-1", appeal, id("m-2"), "PLAYER", "PLAYER", 30, true);

        assertEquals(0, this.queries.count(InboxSection.NEEDS_ANSWER), "the deleted message is no longer visible");
    }

    @Test
    void openAppealsExcludesAnAppealThatHasADecision() {
        punishment("p-1", "OPEN", null, 10);
        punishment("p-2", "OPEN", null, 11);
        appeal(id("pending"), AppealStatus.ELIGIBLE_PENDING_REVIEW, "p-1", 10);
        appeal(id("decided"), AppealStatus.DENIED, "p-2", 11);

        assertEquals(List.of(id("pending")), ids(this.queries.oldest(InboxSection.OPEN_APPEALS, 10)));
        assertEquals(1, this.queries.count(InboxSection.OPEN_APPEALS));
    }

    @Test
    void newReportsListsOnlyReportsThatAreStillOpen() {
        report(id("open"), ReportStatus.OPEN, 10);
        report(id("review"), ReportStatus.UNDER_REVIEW, 11);

        assertEquals(List.of(id("open")), ids(this.queries.oldest(InboxSection.NEW_REPORTS, 10)));
        assertEquals(1, this.queries.count(InboxSection.NEW_REPORTS));
    }

    @Test
    void unassignedCasesExcludesACaseThatHasAnAssignee() {
        punishment("p-free", "OPEN", null, 10);
        punishment("p-taken", "OPEN", STAFF, 11);

        assertEquals(List.of("p-free"), ids(this.queries.oldest(InboxSection.UNASSIGNED_CASES, 10)));
        assertEquals(1, this.queries.count(InboxSection.UNASSIGNED_CASES));
    }

    @Test
    void unassignedCasesExcludesACaseThatIsClosed() {
        punishment("p-closed", "CLOSED", null, 10);

        assertEquals(0, this.queries.count(InboxSection.UNASSIGNED_CASES));
    }

    @Test
    void aSectionListsAtMostTheRequestedNumberOfItemsOldestFirst() {
        for (int i = 0; i < 12; i++) {
            punishment("p-" + i, "OPEN", null, 100 + i);
        }

        List<InboxDTO.Item> first = this.queries.oldest(InboxSection.UNASSIGNED_CASES, 10);

        assertEquals(10, first.size(), "the query is bounded by the limit");
        assertEquals("p-0", first.getFirst().id(), "the oldest case comes first");
        assertEquals(12, this.queries.count(InboxSection.UNASSIGNED_CASES), "the count is not bounded");
        assertTrue(first.stream().allMatch(item -> item.owner().equals(OWNER)), "the owner is the hash, never a raw UUID");
    }

    @Test
    void anUnreadableDatabaseIsReportedAsUnavailable() {
        InboxQueries unreachable = new InboxQueries(new org.postgresql.ds.PGSimpleDataSource() {{
            setUrl("jdbc:postgresql://127.0.0.1:1/blackhole");
            setUser("nobody");
            setPassword("nothing");
        }});

        assertThrows(InboxUnavailableException.class, () -> unreachable.count(InboxSection.NEW_REPORTS));
    }
}
