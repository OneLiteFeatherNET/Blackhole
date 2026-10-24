package net.onelitefeather.blackhole.backend.publicapi;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChangeNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player's seen marker: one number per case, moved by the conversation read, never backwards, with no timestamp.
 */
@Tag("integration")
class PlayerSeenIntegrationTest extends PostgresIntegrationTest {

    private static final String ID = "seen-1";

    private PublicDatabase db;
    private RecordingNotifier notifier;
    private JdbcPlayerSeenStore seen;

    private static final class RecordingNotifier implements CaseChangeNotifier {

        final List<String> seenMoves = new ArrayList<>();

        @Override
        public void appended(String punishmentIdentifier, long seq, String type) {
        }

        @Override
        public void sessionsEnded(String punishmentIdentifier) {
        }

        @Override
        public void seenMoved(String punishmentIdentifier, long seq) {
            this.seenMoves.add(punishmentIdentifier + "@" + seq);
        }
    }

    @BeforeEach
    void givenACaseWithoutAnyRead() {
        this.db = new PublicDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        this.db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, 'network', false, false, 1, 'SEENSEEN', '{}'::jsonb)", ID, UUID.nameUUIDFromBytes("staff".getBytes()), "ab".repeat(64));
        this.db.execute("INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at) "
                + "VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, 1, 1)", ID);
        this.notifier = new RecordingNotifier();
        this.seen = new JdbcPlayerSeenStore(this.db.dataSource, this.notifier);
    }

    private long seenSeq() {
        return ((Number) this.db.column("SELECT player_seen_seq FROM punishment_case WHERE punishment_identifier = ?", ID).getFirst()).longValue();
    }

    @Test
    void aReadMovesTheMarkerToTheHighestSeqItReturned() {
        this.seen.moveTo(ID, 40);

        assertEquals(40, seenSeq());
    }

    @Test
    void theMarkerNeverMovesBackwards() {
        this.seen.moveTo(ID, 40);

        this.seen.moveTo(ID, 30);

        assertEquals(40, seenSeq(), "a stale read cannot unsee what the player already read");
    }

    @Test
    void aMoveIsReportedToStaffOnlyWhenTheMarkerChanged() {
        assertTrue(this.seen.moveTo(ID, 40));
        assertFalse(this.seen.moveTo(ID, 40), "the same read again changes nothing");
        assertFalse(this.seen.moveTo(ID, 12), "a stale read changes nothing");

        assertEquals(List.of(ID + "@40"), this.notifier.seenMoves);
    }

    @Test
    void theMarkerIsOneNumberWithoutAnyTimestamp() {
        List<Object> columns = this.db.column("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = 'punishment_case' AND column_name LIKE '%seen%'");

        assertEquals(List.of("player_seen_seq"), columns);
    }

    @Test
    void aCaseWithNoReadStartsAtZero() {
        assertEquals(0, seenSeq());
    }
}
