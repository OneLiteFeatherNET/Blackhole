package net.onelitefeather.blackhole.backend.ipban;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The IP ban table against the real migrations: the partial unique index and the closing rules. */
@Tag("integration")
class JdbcIpBanStoreIntegrationTest extends PostgresIntegrationTest {

    private static final long NOW = 1_800_000_000_000L;

    private JdbcIpBanStore store;

    @BeforeEach
    void givenAMigratedSchema() {
        CaseDatabase db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        this.store = new JdbcIpBanStore(db.dataSource);
    }

    private static IpBanRecord ban(String range, Long expiresAt) {
        return new IpBanRecord(UUID.randomUUID(), IpBanNetwork.parse(range).orElseThrow().network(), "Bot wave", null,
                IpBanSource.PANEL, null, null, IpBanFixture.ADMIN_OID, NOW - 10, expiresAt, null, null, null);
    }

    @Test
    void aStoredBanReadsBackWithItsRangeAndExpiry() {
        IpBanRecord record = ban("2001:db8::/48", NOW + 1000);
        assertTrue(this.store.insert(record), "the row is stored");

        IpBanRecord read = this.store.find(record.id()).orElseThrow();

        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0000/48", read.range(), "the canonical range survives the round trip");
        assertEquals(NOW + 1000, read.expiresAt(), "the expiry survives the round trip");
        assertEquals(IpBanSource.PANEL, read.source(), "the source survives the round trip");
    }

    @Test
    void theDatabaseRefusesASecondActiveBanOfTheSameRange() {
        assertTrue(this.store.insert(ban("203.0.113.7", null)), "the first ban is stored");

        assertFalse(this.store.insert(ban("203.0.113.7", null)), "the partial unique index holds one active row per range");
    }

    @Test
    void aLiftedRangeCanBeBannedAgain() {
        IpBanRecord first = ban("203.0.113.7", null);
        this.store.insert(first);
        this.store.lift(first.id(), IpBanFixture.ADMIN_OID, "False positive", NOW);

        assertTrue(this.store.insert(ban("203.0.113.7", null)), "a lifted row no longer holds the range");
    }

    @Test
    void anExpiredRowIsClosedBeforeTheRangeIsBannedAgain() {
        IpBanRecord expired = ban("203.0.113.7", NOW - 1);
        this.store.insert(expired);

        assertEquals(1, this.store.liftExpired("203.0.113.7/32", NOW), "the expired row is closed");
        assertTrue(this.store.insert(ban("203.0.113.7", null)), "and the range can be banned again");
    }
}
