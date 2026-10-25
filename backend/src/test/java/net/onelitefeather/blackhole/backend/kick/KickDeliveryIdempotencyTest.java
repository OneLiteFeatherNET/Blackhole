package net.onelitefeather.blackhole.backend.kick;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import net.onelitefeather.blackhole.backend.kick.KickStore.Delivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One row per (kick, proxy), however often the proxy answers; against the real schema. */
@Tag("integration")
class KickDeliveryIdempotencyTest extends PostgresIntegrationTest {

    private CaseDatabase db;
    private JdbcKickStore store;
    private final UUID kick = UUID.randomUUID();

    @BeforeEach
    void givenAMigratedSchema() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        store = new JdbcKickStore(db.dataSource);
    }

    private long rows() {
        return db.count("SELECT count(*) FROM kick_delivery WHERE subject_id = ?", kick);
    }

    @Test
    void theSameProxyAcknowledgingTwiceStoresOneRow() {
        store.recordDelivery(kick, new Delivery("proxy-1", true, true, 10L));
        store.recordDelivery(kick, new Delivery("proxy-1", true, true, 20L));

        assertEquals(1, rows(), "one row per kick and proxy");
    }

    @Test
    void anotherProxyGetsItsOwnRow() {
        store.recordDelivery(kick, new Delivery("proxy-1", true, true, 10L));
        store.recordDelivery(kick, new Delivery("proxy-2", false, false, 11L));

        assertEquals(2, rows(), "one row per proxy");
        assertEquals(2, store.deliveries(kick).size());
    }

    @Test
    void aLaterAnswerNeverTurnsAPositiveFlagOff() {
        store.recordDelivery(kick, new Delivery("proxy-1", true, true, 10L));
        store.recordDelivery(kick, new Delivery("proxy-1", false, false, 20L));

        Delivery stored = store.deliveries(kick).getFirst();

        assertTrue(stored.found() && stored.moved(), "the positive answer stays");
        assertEquals(10L, stored.ackedAt(), "the first answer time stays");
    }

    @Test
    void aLaterPositiveAnswerTurnsTheFlagsOn() {
        store.recordDelivery(kick, new Delivery("proxy-1", false, false, 10L));
        store.recordDelivery(kick, new Delivery("proxy-1", true, true, 20L));

        Delivery stored = store.deliveries(kick).getFirst();

        assertTrue(stored.found() && stored.moved(), "the better answer wins");
        assertEquals(1, rows());
    }

    @Test
    void aMassKickActionIsStoredOnceAndAnotherInsertWithTheSameIdIsIgnored() {
        KickStore.Action action = new KickStore.Action(kick, KickScope.SERVER, "Lobby-1", "maintenance", null, 1L, 6L);

        assertTrue(store.insertAction(action), "first insert");
        assertEquals(false, store.insertAction(action), "second insert is a no-op");
        assertEquals(KickScope.SERVER, store.findAction(kick).orElseThrow().scope());
    }
}
