package net.onelitefeather.blackhole.backend.publicapi;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staff author of a conversation entry: the snapshot name recorded with the message and the live avatar key of the
 * account, read in one statement. A staff-only message never leaves the database.
 */
@Tag("integration")
class JdbcPublicConversationReaderTest extends PostgresIntegrationTest {

    private static final String PUNISHMENT = "conv-staff";
    private static final UUID MIA = UUID.nameUUIDFromBytes("mia".getBytes());
    private static final UUID RITA = UUID.nameUUIDFromBytes("rita".getBytes());

    private PublicDatabase db;
    private JdbcPublicConversationReader reader;
    private long clock;

    @BeforeEach
    void givenAMigratedSchemaWithOnePunishment() {
        db = new PublicDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        reader = new JdbcPublicConversationReader(db.dataSource);
        clock = 100L;
        db.execute("INSERT INTO punishments (identifier, source, owner, section_key, permanent, lifted, created_at, short_id, meta_data) "
                + "VALUES (?, ?, ?, 'network', false, false, 1, 'staffcas', '{}'::jsonb)", PUNISHMENT, MIA, "ab".repeat(64));
        db.execute("INSERT INTO punishment_case (punishment_identifier, status, priority, tags, version, created_at, updated_at) "
                + "VALUES (?, 'OPEN', 'NORMAL', '[]'::jsonb, 0, 1, 1)", PUNISHMENT);
    }

    private void profile(UUID oid, String mode, String avatarKey, Long anonymizedAt) {
        db.execute("INSERT INTO staff_profile (oid, display_name, display_name_folded, avatar_mode, avatar_key, updated_at, anonymized_at) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?)", oid, "Live Name", "live name", mode, avatarKey, anonymizedAt);
    }

    /** A message by a staff member, with an optional snapshot name in its author object (payload version 2 when set). */
    private void staffMessage(String messageId, String visibility, UUID actor, String snapshot) {
        String author = snapshot == null ? "{\"kind\":\"STAFF\"}" : "{\"kind\":\"STAFF\",\"staffDisplayName\":\"" + snapshot + "\"}";
        db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
                + "actor_oid, visibility, subject_id, payload) VALUES (?, ?, 'message.added', ?, ?, 'LIVE', 'VERIFIED', ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), PUNISHMENT, snapshot == null ? 1 : 2, clock++, actor, visibility, messageId,
                "{\"messageId\":\"" + messageId + "\",\"author\":" + author + ",\"channel\":\"PANEL\",\"text\":\"hi\"}");
    }

    private void playerMessage(String messageId) {
        db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
                + "visibility, subject_id, payload) VALUES (?, ?, 'message.added', 1, ?, 'LIVE', 'PLAYER', 'PLAYER', ?, ?::jsonb)",
                UUID.randomUUID(), PUNISHMENT, clock++, messageId,
                "{\"messageId\":\"" + messageId + "\",\"author\":{\"kind\":\"PLAYER\"},\"channel\":\"PORTAL\",\"text\":\"help\"}");
    }

    private List<JdbcPublicConversationReader.Entry> read() {
        return reader.read(PUNISHMENT, null, 50).entries();
    }

    @Test
    void aStaffEntryCarriesTheSnapshotNameAndTheLiveAvatarKey() {
        profile(MIA, "AUTO", "avatar-live-key", null);
        staffMessage("m-1", "PLAYER", MIA, "Mia K.");

        JdbcPublicConversationReader.Entry entry = read().getFirst();

        assertEquals("STAFF", entry.author());
        assertEquals("Mia K.", entry.staff().displayName());
        assertEquals("avatar-live-key", entry.staff().avatarKey());
    }

    @Test
    void initialsModeAndAnUnlinkedAccountHaveNoAvatarKey() {
        profile(MIA, "INITIALS", "avatar-live-key", null);
        staffMessage("m-1", "PLAYER", MIA, "Mia K.");

        assertNull(read().getFirst().staff().avatarKey(), "initials are shown instead of the head");
    }

    @Test
    void aVersionOneStaffEntryHasNoNameButStillHasTheLiveAvatar() {
        profile(MIA, "AUTO", "avatar-live-key", null);
        staffMessage("m-1", "PLAYER", MIA, null);

        JdbcPublicConversationReader.Entry entry = read().getFirst();

        assertNull(entry.staff().displayName(), "a message written before names existed keeps no name");
        assertEquals("avatar-live-key", entry.staff().avatarKey());
    }

    @Test
    void anAnonymisedAccountLosesItsAvatarInTheConversation() {
        profile(MIA, "AUTO", "avatar-live-key", 5L);
        staffMessage("m-1", "PLAYER", MIA, null);

        assertNull(read().getFirst().staff().avatarKey());
    }

    @Test
    void aPlayerEntryAndAMarkerHaveNoStaffAuthor() {
        playerMessage("m-1");
        db.execute("INSERT INTO case_event (identifier, punishment_identifier, type, payload_version, occurred_at, origin, actor_trust, "
                + "visibility, subject_id, payload) VALUES (?, ?, 'appeal.filed', 1, ?, 'LIVE', 'UNVERIFIED', 'STAFF', 'a-1', "
                + "'{\"appealId\":\"a-1\",\"eligible\":true}'::jsonb)", UUID.randomUUID(), PUNISHMENT, clock++);

        List<JdbcPublicConversationReader.Entry> entries = read();

        assertEquals(2, entries.size());
        assertNull(entries.get(0).staff(), "a player is never named as staff");
        assertNull(entries.get(1).staff(), "a marker has no staff author");
    }

    @Test
    void aStaffOnlyMessageWithANameNeverReachesThePlayer() {
        profile(RITA, "AUTO", "avatar-rita", null);
        staffMessage("m-1", "STAFF", RITA, "Rita V.");

        assertTrue(read().isEmpty(), "the name and the head of a staff-only message stay in the case");
    }
}
