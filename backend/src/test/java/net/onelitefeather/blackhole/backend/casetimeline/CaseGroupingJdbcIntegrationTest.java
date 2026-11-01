package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player column and the groups by player against a real PostgreSQL schema: the newest name, the owner
 * filter, group counts across the filters and the paging over owners.
 */
@Tag("integration")
class CaseGroupingJdbcIntegrationTest extends PostgresIntegrationTest {

    /** A 128-character owner hash, the real length of {@code punishments.owner} and {@code player_name_history.owner_hash}. */
    private static final String OWNER = "ab".repeat(64);
    private static final String OTHER = "cd".repeat(64);
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID PLAYER = UUID.fromString("0a1b2c3d-0000-4000-8000-00000000cafe");
    private static final CaseActor ACTOR = new CaseActor(ActorTrust.VERIFIED, STAFF, STAFF, null);

    private CaseDatabase db;
    private JdbcCaseWriter writer;
    private JdbcCaseReader reader;

    @BeforeEach
    void givenAMigratedSchema() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        writer = new JdbcCaseWriter(db.dataSource);
        reader = new JdbcCaseReader(db.dataSource);
    }

    private void givenCase(String id, String owner, long createdAt, String status, List<String> tags) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, ?, 'network', false, false, ?, ?, '{}'::jsonb)", id, STAFF, STAFF, owner, createdAt, "S" + id);
        writer.ensureCase(id, createdAt);
        if (!"OPEN".equals(status) || !tags.isEmpty()) {
            writer.update(id, 0, createdAt, ACTOR, CaseMeta.defaults(), new CaseMeta(status, null, "NORMAL", tags));
        }
    }

    private void givenName(String owner, String name, long lastSeen) {
        db.execute("INSERT INTO player_name_history (owner_hash, player_uuid, name_lower, name, first_seen, last_seen) VALUES (?, ?, ?, ?, ?, ?)",
                owner, PLAYER, name.toLowerCase(), name, 1L, lastSeen);
    }

    private static CaseReader.ListQuery list(String owner, int page, int size) {
        return new CaseReader.ListQuery(null, null, null, null, null, null, page, size, owner);
    }

    @Test
    void aListedCaseCarriesTheNewestNameOfItsOwner() {
        givenCase("c-1", OWNER, 10L, "OPEN", List.of());
        givenName(OWNER, "OldName", 10L);
        givenName(OWNER, "NewName", 20L);

        CaseReader.Listed item = reader.list(list(null, 0, 20), 1_000L).items().getFirst();

        assertEquals("NewName", item.playerName(), "the name with the latest last_seen wins");
    }

    @Test
    void aListedCaseWithoutAnyNameHasNoPlayerName() {
        givenCase("c-1", OTHER, 10L, "OPEN", List.of());

        CaseReader.Listed item = reader.list(list(null, 0, 20), 1_000L).items().getFirst();

        assertNull(item.playerName(), "a removed or never seen name reads as null");
        assertEquals(OTHER, item.owner());
    }

    @Test
    void theNameJoinWorksForA128CharacterOwnerHash() {
        givenCase("c-1", OWNER, 10L, "OPEN", List.of());
        givenName(OWNER, "Matched", 10L);

        assertEquals("Matched", reader.list(list(null, 0, 20), 1_000L).items().getFirst().playerName());
    }

    @Test
    void theListCarriesTheVersionOfTheCase() {
        givenCase("c-1", OWNER, 10L, "IN_REVIEW", List.of());

        assertEquals(1L, reader.list(list(null, 0, 20), 1_000L).items().getFirst().version());
    }

    @Test
    void theOwnerFilterListsOnlyTheCasesOfThatOwner() {
        givenCase("c-1", OWNER, 10L, "OPEN", List.of());
        givenCase("c-2", OWNER, 20L, "CLOSED", List.of());
        givenCase("c-3", OTHER, 30L, "OPEN", List.of());

        List<String> ids = reader.list(list(OWNER, 0, 20), 1_000L).items().stream().map(CaseReader.Listed::identifier).toList();

        assertEquals(List.of("c-2", "c-1"), ids, "newest activity first");
    }

    @Test
    void aPageOfTheListIsReadInOneStatement() {
        givenCase("c-1", OWNER, 10L, "OPEN", List.of());
        givenCase("c-2", OTHER, 20L, "OPEN", List.of());
        AtomicInteger statements = new AtomicInteger();
        JdbcCaseReader counting = new JdbcCaseReader(countingStatements(statements));

        counting.list(list(null, 0, 20), 1_000L);

        assertEquals(1, statements.get(), "the rows and their names come in one statement per page");
    }

    @Test
    void aGroupCountsTheOpenCasesAndAllCasesOfThePlayer() {
        givenCase("c-1", OWNER, 10L, "OPEN", List.of());
        givenCase("c-2", OWNER, 20L, "OPEN", List.of());
        givenCase("c-3", OWNER, 30L, "WAITING", List.of());
        givenCase("c-4", OWNER, 40L, "CLOSED", List.of());

        CaseReader.Group group = reader.groups(list(null, 0, 20), 1_000L).items().getFirst();

        assertEquals(OWNER, group.owner());
        assertEquals(3, group.openCount(), "every status except CLOSED is open");
        assertEquals(4, group.totalCount());
        assertEquals(40L, group.lastActivityAt());
    }

    @Test
    void aGroupCountsOnlyTheCasesThatMatchTheFilters() {
        givenCase("c-1", OWNER, 10L, "OPEN", List.of("appeal"));
        givenCase("c-2", OWNER, 20L, "CLOSED", List.of());
        givenCase("c-3", OWNER, 30L, "OPEN", List.of());
        CaseReader.ListQuery tagged = new CaseReader.ListQuery(null, null, "appeal", null, null, null, 0, 20, null);

        CaseReader.Group group = reader.groups(tagged, 1_000L).items().getFirst();

        assertEquals(1, group.totalCount());
        assertEquals(1, group.openCount());
    }

    @Test
    void anOwnerWithNoMatchingCaseHasNoGroup() {
        givenCase("c-1", OWNER, 10L, "OPEN", List.of());
        CaseReader.ListQuery closedOnly = new CaseReader.ListQuery("CLOSED", null, null, null, null, null, 0, 20, null);

        assertTrue(reader.groups(closedOnly, 1_000L).items().isEmpty(), "a group exists only with a matching case");
    }

    @Test
    void aPlayerNeverSplitsAcrossGroupPages() {
        givenCase("a-1", OWNER, 10L, "OPEN", List.of());
        givenCase("a-2", OWNER, 20L, "OPEN", List.of());
        givenCase("a-3", OWNER, 30L, "OPEN", List.of());
        givenCase("b-1", OTHER, 5L, "OPEN", List.of());

        CaseReader.GroupPage first = reader.groups(list(null, 0, 1), 1_000L);
        CaseReader.GroupPage second = reader.groups(list(null, 1, 1), 1_000L);

        assertEquals(List.of(OWNER), first.items().stream().map(CaseReader.Group::owner).toList());
        assertEquals(3, first.items().getFirst().totalCount(), "the counts cover all of the player's cases");
        assertEquals(List.of(OTHER), second.items().stream().map(CaseReader.Group::owner).toList());
        assertEquals(2, first.total(), "paging is over players");
    }

    @Test
    void aGroupCarriesTheNameOfItsOwnerOrNull() {
        givenCase("a-1", OWNER, 10L, "OPEN", List.of());
        givenCase("b-1", OTHER, 20L, "OPEN", List.of());
        givenName(OWNER, "Named", 10L);

        List<CaseReader.Group> groups = reader.groups(list(null, 0, 20), 1_000L).items();

        assertEquals("Named", groups.stream().filter(group -> group.owner().equals(OWNER)).findFirst().orElseThrow().playerName());
        assertNull(groups.stream().filter(group -> group.owner().equals(OTHER)).findFirst().orElseThrow().playerName());
    }

    /** A data source that counts the connections it hands out: the reader takes one per statement. */
    private DataSource countingStatements(AtomicInteger statements) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) {
                statements.incrementAndGet();
            }
            try {
                return method.invoke(db.dataSource, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
