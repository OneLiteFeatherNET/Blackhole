package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs the VPN detection migration on a fresh schema and checks the record store's SQL against real PostgreSQL. */
@Tag("integration")
class JdbcIntelRecordStoreIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "c".repeat(128);

    private JdbcIntelRecordStore store;

    @BeforeEach
    void freshSchema() {
        String schema = "vpnrec_" + Long.toHexString(System.nanoTime());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setCurrentSchema(schema);
        this.store = new JdbcIntelRecordStore(dataSource);
    }

    @Test
    void theSeededPoliciesAreFlagUntilAnAdminChangesOne() {
        assertEquals(Map.of(IntelClass.VPN, IntelPolicy.FLAG, IntelClass.HOSTING, IntelPolicy.FLAG, IntelClass.PROXY,
                IntelPolicy.FLAG), this.store.loadPolicies(), "the three seeded rows are FLAG");

        this.store.savePolicy(IntelClass.VPN, IntelPolicy.DENY, 1L);
        this.store.savePolicy(IntelClass.VPN, IntelPolicy.ALLOW, 2L);

        assertEquals(IntelPolicy.ALLOW, this.store.loadPolicies().get(IntelClass.VPN), "the upsert replaces the value");
    }

    @Test
    void anAllowEntryWithoutExpiryReadsBackAsPermanent() {
        UUID actor = UUID.randomUUID();
        this.store.saveAllowEntry(new IntelAllowEntry(OWNER, "verified by the network owner", actor, null, 10L));

        IntelAllowEntry read = this.store.loadAllowList().getFirst();

        assertEquals(OWNER, read.ownerHash(), "the owner hash is the key");
        assertEquals(actor, read.actorOid(), "the actor is kept");
        assertNull(read.expiresAt(), "a null expiry stays permanent");
    }

    @Test
    void hitsAreReadNewestFirstAndOldOnesAreDeleted() {
        this.store.insertHit(hit(100L, IntelPolicy.FLAG));
        this.store.insertHit(hit(300L, IntelPolicy.DENY));
        this.store.insertHit(hit(200L, IntelPolicy.FLAG));

        List<IntelHit> newest = this.store.hitsOf(OWNER, 50);
        assertEquals(List.of(300L, 200L, 100L), newest.stream().map(IntelHit::createdAt).toList(), "newest first");
        assertNull(newest.getFirst().asn(), "an unknown ASN stays null");

        assertEquals(2, this.store.deleteHitsCreatedBefore(250L), "the two hits before the cutoff are deleted");
        assertEquals(1, this.store.hitsOf(OWNER, 50).size(), "the newest hit stays");
    }

    private static IntelHit hit(long createdAt, IntelPolicy verdict) {
        return new IntelHit(UUID.randomUUID(), OWNER, "192.0.2.10", "VPN", "list:x4bnet-vpn", null, null, verdict, false,
                createdAt);
    }
}
