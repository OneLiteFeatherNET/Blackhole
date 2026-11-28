package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The join_guard_state row against the real schema: the seeded row, and one conditional update wins a race. */
@Tag("integration")
class JdbcGuardStateStoreTest extends PostgresIntegrationTest {

    private JdbcGuardStateStore store;

    @BeforeEach
    void givenAMigratedSchema() {
        CaseDatabase db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        store = new JdbcGuardStateStore(db.dataSource);
    }

    @Test
    void theSeededRowStartsWithEveryLevelOff() {
        GuardState state = store.current().orElseThrow();

        assertEquals(GuardState.initial(0L), state, "the migration must seed one row with every level OFF");
    }

    @Test
    void aConditionalUpdateFromTheSeededStateSucceedsOnce() {
        GuardState seeded = store.current().orElseThrow();
        GuardState strict = GuardState.of(GuardLevel.OFF, GuardLevel.STRICT, 1L);

        assertTrue(store.compareAndSet(seeded, strict), "the first update from the seeded state must succeed");
        assertFalse(store.compareAndSet(seeded, strict), "a second update from the same stale state must not succeed");
        assertEquals(strict, store.current().orElseThrow(), "the row must hold the first writer's state");
    }
}
