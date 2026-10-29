package net.onelitefeather.blackhole.backend.intel.names;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The player_name_history table against the real schema: the upsert, the old-name lookup and the deletes. */
@Tag("integration")
class JdbcNameHistoryStoreTest extends PostgresIntegrationTest {

    private static final String OWNER = "a".repeat(128);
    private static final String OTHER = "b".repeat(128);

    private JdbcNameHistoryStore store;

    @BeforeEach
    void givenAMigratedSchema() {
        CaseDatabase db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        store = new JdbcNameHistoryStore(db.dataSource);
    }

    @Test
    void aRepeatedNameInAnotherCaseKeepsOneRowAndMovesLastSeenForward() {
        UUID player = UUID.randomUUID();
        store.upsert(OWNER, player, "Steve", 1_000L);

        store.upsert(OWNER, player, "sTEVE", 2_000L);

        List<NameRecord> names = store.findByOwner(OWNER, 10, 0);
        assertEquals(1, names.size(), "one row for the name in any case");
        assertEquals("sTEVE", names.getFirst().name(), "the newest casing");
        assertEquals(1_000L, names.getFirst().firstSeen(), "first seen is kept");
        assertEquals(2_000L, names.getFirst().lastSeen(), "last seen moves forward");
        assertEquals(player, names.getFirst().playerUuid(), "the raw UUID is kept");
    }

    @Test
    void anOldNameFindsItsOwnerWithTheOwnersCurrentName() {
        UUID player = UUID.randomUUID();
        store.upsert(OWNER, player, "Steve", 1_000L);
        store.upsert(OWNER, player, "Alex", 2_000L);

        List<OwnerRecord> owners = store.findOwnersOfName("steve", 20);

        assertEquals(1, owners.size(), "the owner that used the name");
        assertEquals("Alex", owners.getFirst().currentName(), "the newest name of the owner");
        assertEquals(1_000L, owners.getFirst().firstSeen(), "first seen of the matched name");
        assertEquals(1_000L, owners.getFirst().lastSeen(), "last seen of the matched name");
    }

    @Test
    void deletingAnOwnerRemovesOnlyItsNames() {
        store.upsert(OWNER, UUID.randomUUID(), "Steve", 1_000L);
        store.upsert(OWNER, UUID.randomUUID(), "Alex", 2_000L);
        store.upsert(OTHER, UUID.randomUUID(), "Steve", 3_000L);

        assertEquals(2, store.deleteByOwner(OWNER), "both names of the owner");

        assertEquals(List.of(), store.findByOwner(OWNER, 10, 0), "the erased owner has no names");
        assertEquals(1, store.findByOwner(OTHER, 10, 0).size(), "the other owner keeps its name");
    }

    @Test
    void theRetentionDeletesOnlyNamesLastSeenBeforeTheCutoff() {
        store.upsert(OWNER, UUID.randomUUID(), "Old", 1_000L);
        store.upsert(OWNER, UUID.randomUUID(), "Recent", 9_000L);

        assertEquals(1, store.deleteLastSeenBefore(5_000L), "only the name last seen before the cutoff");

        assertTrue(store.findOwnersOfName("Old", 20).isEmpty());
        assertEquals(1, store.findOwnersOfName("Recent", 20).size());
    }
}
