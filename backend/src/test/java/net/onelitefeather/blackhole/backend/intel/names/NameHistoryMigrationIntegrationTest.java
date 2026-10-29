package net.onelitefeather.blackhole.backend.intel.names;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs V34 over sightings that exist at V32, then checks the backfill of the name history from them. */
@Tag("integration")
class NameHistoryMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "a".repeat(128);
    private static final String OTHER = "b".repeat(128);

    private static void sighting(CaseDatabase db, String owner, UUID uuid, String name, long startedAt) {
        db.execute("INSERT INTO player_sighting (session_id, owner_hash, player_uuid, player_name, ip, outcome, started_at, created_at)"
                + " VALUES (?, ?, ?, ?, '192.0.2.10', 'ALLOWED', ?, ?)", UUID.randomUUID(), owner, uuid, name, startedAt, startedAt);
    }

    @Test
    void theBackfillKeepsOneRowPerOwnerAndLowerCaseNameWithTheUuidAndTheEarliestAndLatestLogin() {
        UUID player = UUID.randomUUID();
        CaseDatabase db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("32");
        sighting(db, OWNER, player, "Steve", 1_000L);
        sighting(db, OWNER, player, "steve", 3_000L);
        sighting(db, OWNER, player, "Alex", 2_000L);
        sighting(db, OTHER, UUID.randomUUID(), "Steve", 4_000L);

        db.migrate("latest");

        assertEquals(2L, db.count("SELECT count(*) FROM player_name_history WHERE owner_hash = ?", OWNER),
                "one row per lower-case name of the owner");
        assertEquals(player, db.column("SELECT player_uuid FROM player_name_history WHERE owner_hash = ? AND name_lower = 'steve'", OWNER)
                .getFirst(), "the raw UUID is carried over");
        assertEquals("steve", db.text("SELECT name FROM player_name_history WHERE owner_hash = ? AND name_lower = 'steve'", OWNER),
                "the newest casing is kept");
        assertEquals(1_000L, ((Number) db.column("SELECT first_seen FROM player_name_history WHERE owner_hash = ? AND name_lower = 'steve'", OWNER)
                .getFirst()).longValue(), "the earliest login of the name");
        assertEquals(3_000L, ((Number) db.column("SELECT last_seen FROM player_name_history WHERE owner_hash = ? AND name_lower = 'steve'", OWNER)
                .getFirst()).longValue(), "the latest login of the name");
        assertEquals(1L, db.count("SELECT count(*) FROM player_name_history WHERE owner_hash = ?", OTHER),
                "the other owner gets its own row");
    }
}
