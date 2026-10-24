package net.onelitefeather.blackhole.backend.publicapi;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakReason;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakSource;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdReissueReason;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.JdbcCaseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A leak mark and a reissue end the player sessions of that punishment in the very statement that changes the case. */
@Tag("integration")
class SessionRevocationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final CaseActor ACTOR = new CaseActor(ActorTrust.VERIFIED, STAFF, STAFF, null);

    private PublicDatabase db;
    private JdbcCaseWriter writer;

    @BeforeEach
    void givenAMigratedSchemaWithTwoPunishmentsAndTheirSessions() {
        db = new PublicDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        writer = new JdbcCaseWriter(db.dataSource);
        for (String id : new String[]{"mine", "yours"}) {
            db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, meta_data, ban_id_hash, ban_id_enc) "
                    + "VALUES (?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb, ?, ?)",
                    id, STAFF, "ab".repeat(64), id.substring(0, 1).toUpperCase().repeat(8), id.getBytes(), new byte[]{1});
            writer.ensureCase(id, 10L);
        }
        givenSession("h-mine-1", "mine");
        givenSession("h-mine-2", "mine");
        givenSession("h-yours-1", "yours");
    }

    private void givenSession(String hash, String punishment) {
        db.execute("INSERT INTO player_session (token_hash, punishment_identifier, scope, csrf, created_at, last_seen_at, expires_at) "
                + "VALUES (?, ?, 'FULL', 'c', 1, 1, 9999999999999)", hash, punishment);
    }

    private long sessions(String punishment) {
        return db.count("SELECT count(*) FROM player_session WHERE punishment_identifier = ?", punishment);
    }

    @Test
    void markingTheBanIdLeakedDeletesAllSessionsOfThatPunishmentAndNoOthers() {
        CaseChanges.LeakMarked marked = writer.markBanIdLeaked("mine", BanIdLeakReason.SHARED, BanIdLeakSource.STAFF, 60L, ACTOR);

        assertNotNull(marked);
        assertEquals(0, sessions("mine"));
        assertEquals(1, sessions("yours"));
    }

    @Test
    void aSecondMarkChangesNothingSoASessionOfTheLimitedLoginSurvivesIt() {
        writer.markBanIdLeaked("mine", BanIdLeakReason.SHARED, BanIdLeakSource.STAFF, 60L, ACTOR);
        db.execute("INSERT INTO player_session (token_hash, punishment_identifier, scope, csrf, created_at, last_seen_at, expires_at) "
                + "VALUES ('h-limited', 'mine', 'LIMITED', 'c', 61, 61, 9999999999999)");

        CaseChanges.LeakMarked again = writer.markBanIdLeaked("mine", BanIdLeakReason.OTHER, BanIdLeakSource.STAFF, 62L, ACTOR);

        assertNull(again, "idempotent: nothing is appended");
        assertEquals(1, sessions("mine"), "the limited session of someone who holds the leaked ID is not touched by a repeat mark");
    }

    @Test
    void reissuingTheBanIdDeletesAllSessionsOfThatPunishmentAndNoOthers() {
        writer.markBanIdLeaked("mine", BanIdLeakReason.SHARED, BanIdLeakSource.STAFF, 60L, ACTOR);
        db.execute("INSERT INTO player_session (token_hash, punishment_identifier, scope, csrf, created_at, last_seen_at, expires_at) "
                + "VALUES ('h-limited', 'mine', 'LIMITED', 'c', 61, 61, 9999999999999)");

        CaseChanges.Reissued reissued = writer.reissueBanId("mine", new byte[]{7, 7}, new byte[]{8}, BanIdReissueReason.NOT_PLAYERS_FAULT, 70L, ACTOR);

        assertNotNull(reissued);
        assertEquals(0, sessions("mine"), "the old ID is dead, so is everything it opened");
        assertEquals(1, sessions("yours"));
    }

    @Test
    void aReissueThatChangesNothingKeepsTheSessions() {
        db.execute("UPDATE punishments SET ban_id_hash = NULL, ban_id_enc = NULL WHERE identifier = 'mine'");

        assertNull(writer.reissueBanId("mine", new byte[]{7}, new byte[]{8}, BanIdReissueReason.OTHER, 70L, ACTOR));

        assertEquals(2, sessions("mine"));
    }

    @Test
    void aReissueThatCollidesRollsTheSessionDeleteBackWithTheRest() {
        org.junit.jupiter.api.Assertions.assertThrows(net.onelitefeather.blackhole.backend.casetimeline.BanIdCollisionException.class,
                () -> writer.reissueBanId("mine", "yours".getBytes(), new byte[]{8}, BanIdReissueReason.OTHER, 70L, ACTOR));

        assertEquals(2, sessions("mine"), "one statement: all or nothing");
    }
}
