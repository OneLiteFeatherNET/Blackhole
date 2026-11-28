package net.onelitefeather.blackhole.backend.areaban;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The area_ban table against the real schema: one unlifted ban per area, and a lift keeps the row as history. */
@Tag("integration")
class JdbcAreaBanStoreTest extends PostgresIntegrationTest {

    private JdbcAreaBanStore store;

    @BeforeEach
    void givenAMigratedSchema() {
        CaseDatabase db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        store = new JdbcAreaBanStore(db.dataSource);
    }

    private static AreaBan ban(UUID id) {
        return new AreaBan(id, AreaBan.Kind.ASN, "16276", "hosting wave", AreaBan.Source.MANUAL, "admin-oid", 1_000L,
                null, null, null, null);
    }

    @Test
    void aSecondUnliftedBanOfTheSameAreaIsRefused() {
        assertTrue(store.insert(ban(UUID.randomUUID())), "the first ban of an area must be stored");

        assertFalse(store.insert(ban(UUID.randomUUID())), "a second unlifted ban of the same area must be refused");
    }

    @Test
    void aLiftedBanNoLongerBlocksANewOneOfTheSameArea() {
        UUID first = UUID.randomUUID();
        store.insert(ban(first));
        store.lift(store.findById(first).orElseThrow().lifted(2_000L, "false positive", "admin-oid"));

        assertTrue(store.insert(ban(UUID.randomUUID())), "after a lift the area must accept a new ban");
    }

    @Test
    void aLiftedBanKeepsItsReasonAsHistory() {
        UUID id = UUID.randomUUID();
        store.insert(ban(id));
        store.lift(store.findById(id).orElseThrow().lifted(2_000L, "false positive", "admin-oid"));

        AreaBan history = store.findById(id).orElseThrow();

        assertEquals("false positive", history.liftReason(), "the lift reason must be kept with the row");
    }
}
