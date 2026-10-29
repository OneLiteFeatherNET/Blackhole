package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The player_sighting table against the real schema: a session is stored whole and a repeat id is ignored. */
@Tag("integration")
class JdbcSessionStoreTest extends PostgresIntegrationTest {

    private static final String OWNER = "a".repeat(128);
    private static final String TOKEN = "c".repeat(128);

    private CaseDatabase db;
    private JdbcSessionStore store;

    @BeforeEach
    void givenAMigratedSchema() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        store = new JdbcSessionStore(db.dataSource);
    }

    private static SessionRecord session(UUID id, String owner, long startedAt) {
        return session(id, owner, startedAt, TOKEN);
    }

    private static SessionRecord session(UUID id, String owner, long startedAt, String token) {
        return new SessionRecord(id, owner, UUID.randomUUID(), "Steve", "192.0.2.10", token, 64496L, "Example Transit", "DE",
                null, null, 767, "play.example.net", SessionOutcome.ALLOWED, startedAt, null);
    }

    @Test
    void sightingsOfSeveralTokensReturnEachOwnerThatUsedAnyOfThem() {
        String tokenA = "a1".repeat(64);
        String tokenB = "b2".repeat(64);
        String tokenC = "c3".repeat(64);
        store.insert(session(UUID.randomUUID(), OWNER, 1_000L, tokenA));
        store.insert(session(UUID.randomUUID(), "d".repeat(128), 2_000L, tokenB));
        store.insert(session(UUID.randomUUID(), "e".repeat(128), 3_000L, tokenC));

        List<SessionStore.TokenSighting> found = store.sightingsOfTokens(List.of(tokenA, tokenB), 0L);

        assertEquals(2, found.size(), "one sighting per session of the two tokens");
        assertEquals(java.util.Set.of(OWNER, "d".repeat(128)), found.stream().map(SessionStore.TokenSighting::ownerHash)
                .collect(java.util.stream.Collectors.toSet()), "the owner of the third token is not returned");
    }

    @Test
    void sightingsOfNoTokensAreEmpty() {
        store.insert(session(UUID.randomUUID(), OWNER, 1_000L));

        assertTrue(store.sightingsOfTokens(List.of(), 0L).isEmpty(), "an empty token set matches nothing");
    }

    @Test
    void aStoredSessionKeepsTheClearIpTheRawUuidTheOwnerHashAndTheOutcome() {
        UUID id = UUID.randomUUID();
        SessionRecord record = session(id, OWNER, 1_000L);

        assertTrue(store.insert(record));

        SessionRecord read = store.find(id).orElseThrow();
        assertEquals("192.0.2.10", read.ip());
        assertEquals(record.playerUuid(), read.playerUuid());
        assertEquals(OWNER, read.ownerHash());
        assertEquals(SessionOutcome.ALLOWED, read.outcome());
        assertEquals(TOKEN, read.ipToken());
        assertEquals(64496L, read.asn());
    }

    @Test
    void aRepeatedSessionIdIsNotStoredTwice() {
        UUID id = UUID.randomUUID();
        store.insert(session(id, OWNER, 1_000L));

        assertFalse(store.insert(session(id, OWNER, 2_000L)), "a repeat is ignored");
        assertEquals(1_000L, store.find(id).orElseThrow().startedAt(), "the first record stays");
    }

    @Test
    void theFirstEndTimeIsKept() {
        UUID id = UUID.randomUUID();
        store.insert(session(id, OWNER, 1_000L));

        store.markEnded(id, 5_000L);
        store.markEnded(id, 9_000L);

        assertEquals(5_000L, store.find(id).orElseThrow().endedAt());
    }

    @Test
    void sessionsOfAnOwnerAreReturnedNewestFirst() {
        store.insert(session(UUID.randomUUID(), OWNER, 1_000L));
        store.insert(session(UUID.randomUUID(), OWNER, 3_000L));
        store.insert(session(UUID.randomUUID(), OWNER, 2_000L));

        assertEquals(java.util.List.of(3_000L, 2_000L, 1_000L),
                store.findByOwner(OWNER, 10, 0).stream().map(SessionRecord::startedAt).toList());
    }
}
