package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bulk edit's one statement per case against a real PostgreSQL schema: state and event together, guarded by the version. */
@Tag("integration")
class CaseBulkJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final String BATCH = "00000000-0000-4000-8000-000000000001";

    private CaseDatabase db;
    private JdbcCaseWriter writer;
    private JdbcCaseReader reader;

    @BeforeEach
    void givenAMigratedSchema() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        writer = new JdbcCaseWriter(db.dataSource);
        reader = new JdbcCaseReader(db.dataSource);
    }

    private void givenPunishment(String id, String shortId) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb)", id, STAFF, STAFF, OWNER, shortId);
    }

    private static CaseMeta closed() {
        return new CaseMeta("CLOSED", null, CaseMeta.DEFAULT_PRIORITY, List.of("a"));
    }

    private CaseChanges.Appended bulk(String id, long expectedVersion, CaseMeta after) {
        return writer.bulkEdit(id, expectedVersion, 100L, CaseActor.of(new net.onelitefeather.blackhole.backend.security.Attribution(STAFF, STAFF)),
                CaseMeta.defaults(), after, BATCH);
    }

    @Test
    void aChangedCaseGetsItsStateAndOneBulkEditedEventInOneStatement() {
        givenPunishment("p-1", "SHORT001");
        writer.ensureCase("p-1", 1L);

        CaseChanges.Appended event = bulk("p-1", 0L, closed());

        assertNotNull(event);
        assertEquals(CaseEventPayload.CASE_BULK_EDITED, event.type());
        assertEquals("CLOSED", db.text("SELECT status FROM punishment_case WHERE punishment_identifier = 'p-1'"));
        assertEquals(1, db.count("SELECT version FROM punishment_case WHERE punishment_identifier = 'p-1'"));
        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE type = 'case.bulk_edited' AND punishment_identifier = 'p-1'"));
        assertEquals("bulk:" + BATCH + ":p-1", db.text("SELECT dedupe_key FROM case_event WHERE type = 'case.bulk_edited'"));
        assertEquals("STAFF", db.text("SELECT visibility FROM case_event WHERE type = 'case.bulk_edited'"));
    }

    @Test
    void aStaleVersionWritesNeitherTheStateNorAnEvent() {
        givenPunishment("p-1", "SHORT001");
        writer.ensureCase("p-1", 1L);

        assertNull(bulk("p-1", 7L, closed()), "a version that is not current changes nothing");

        assertEquals("OPEN", db.text("SELECT status FROM punishment_case WHERE punishment_identifier = 'p-1'"));
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE type = 'case.bulk_edited'"));
    }

    @Test
    void aReplayOfTheSameBatchAfterTheChangeAppendsNothing() {
        givenPunishment("p-1", "SHORT001");
        writer.ensureCase("p-1", 1L);
        assertNotNull(bulk("p-1", 0L, closed()));

        assertNull(bulk("p-1", 0L, closed()), "the version moved, so the replay is refused");

        assertEquals(1, db.count("SELECT count(*) FROM case_event WHERE type = 'case.bulk_edited'"));
        assertEquals(1, db.count("SELECT version FROM punishment_case WHERE punishment_identifier = 'p-1'"));
    }

    @Test
    void findAllReadsTheRowsOfSeveralCasesInOneQuery() {
        givenPunishment("p-1", "SHORT001");
        givenPunishment("p-2", "SHORT002");
        writer.ensureCase("p-1", 1L);
        assertTrue(bulk("p-1", 0L, closed()) != null);

        var rows = reader.findAll(List.of("p-1", "p-2", "p-missing"));

        assertEquals(List.of("p-1"), rows.keySet().stream().toList(), "a case without a row is not in the map");
        assertEquals(CaseStatus.CLOSED.name(), rows.get("p-1").meta().status());
        assertEquals(1L, rows.get("p-1").version());
    }
}
