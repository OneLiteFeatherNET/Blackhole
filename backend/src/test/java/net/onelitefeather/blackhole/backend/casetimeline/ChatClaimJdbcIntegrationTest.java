package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message statement that binds chat files, against the real schema (V23): all-or-nothing, only staged files of the scope and of the
 * punishment's owner, idempotent with the dedupe key, and the chat scope stays on a bound file. Plus the provenance default of the migration.
 */
@Tag("integration")
class ChatClaimJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String OTHER_OWNER = "cd".repeat(64);
    private static final String ID = "claim-1";
    private static final UUID APPEAL = UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000a1");
    private static final String SCOPE = ChatFiles.appealScope(APPEAL);
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final int FILE = 4;

    private CaseDatabase db;
    private JdbcCaseWriter writer;

    @BeforeEach
    void givenAMigratedSchema() {
        this.db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        this.writer = new JdbcCaseWriter(this.db.dataSource);
        givenPunishment(ID, "AAAAAAA1", OWNER);
        this.writer.ensureCase(ID, 10L);
    }

    private void givenPunishment(String id, String shortId, String owner) {
        this.db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, "
                + "meta_data, ban_id_hash, ban_id_enc) VALUES (?, ?, ?, ?, 'network', false, false, 1, ?, '{}'::jsonb, ?, ?)",
                id, STAFF, STAFF, owner, shortId, id.getBytes(), new byte[]{1});
    }

    private UUID givenStaged(String scope, String owner) {
        UUID id = UUID.randomUUID();
        this.db.execute("INSERT INTO evidence_items (identifier, owner, correlation_id, kind, source_system, created_at, file_key, file_name, "
                + "file_content_type, file_size, file_sha256, provenance) VALUES (?, ?, ?, ?, 'chat', 5, ?, 'a.png', 'image/png', 10, ?, 1)",
                id, owner, scope, FILE, "key-" + id, "0".repeat(64));
        return id;
    }

    private CaseEventDraft message(String messageId, String text) {
        return CaseDrafts.playerMessage(ID, messageId, APPEAL.toString(), text, 20L);
    }

    private String punishmentOf(UUID file) {
        return this.db.text("SELECT punishment_identifier FROM evidence_items WHERE identifier = ?", file);
    }

    @Test
    void aMessageBindsEveryNamedFileAndKeepsTheirScope() {
        UUID first = givenStaged(SCOPE, OWNER);
        UUID second = givenStaged(SCOPE, OWNER);

        List<CaseChanges.Appended> appended = this.writer.appendMessage(message("m-1", "two files"), true,
                new CaseChanges.Claim(List.of(first, second), SCOPE));

        assertEquals("message.added", appended.getFirst().type());
        assertEquals(ID, punishmentOf(first), "a bound file is case evidence");
        assertEquals(ID, punishmentOf(second));
        assertEquals(2, this.db.count("SELECT count(*) FROM evidence_items WHERE correlation_id = ?", SCOPE), "the scope stays so the quota can count");
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'message.added'"));
    }

    @Test
    void aForeignFileClaimsNothingAndStoresNoMessage() {
        UUID mine = givenStaged(SCOPE, OWNER);
        UUID foreign = givenStaged(ChatFiles.appealScope(UUID.randomUUID()), OWNER);

        List<CaseChanges.Appended> appended = this.writer.appendMessage(message("m-1", "x"), true, new CaseChanges.Claim(List.of(mine, foreign), SCOPE));

        assertTrue(appended.isEmpty(), "all or nothing");
        assertEquals(null, punishmentOf(mine), "the valid file stays staged too");
        assertEquals(null, punishmentOf(foreign));
        assertEquals(0, this.db.count("SELECT count(*) FROM case_event WHERE type = 'message.added'"), "no message");
    }

    @Test
    void anUnknownIdClaimsNothing() {
        UUID mine = givenStaged(SCOPE, OWNER);

        List<CaseChanges.Appended> appended = this.writer.appendMessage(message("m-1", "x"), true,
                new CaseChanges.Claim(List.of(mine, UUID.randomUUID()), SCOPE));

        assertTrue(appended.isEmpty());
        assertEquals(null, punishmentOf(mine));
    }

    @Test
    void aFileOfAnotherOwnerIsNotClaimed() {
        UUID elsewhere = givenStaged(SCOPE, OTHER_OWNER);

        List<CaseChanges.Appended> appended = this.writer.appendMessage(message("m-1", "x"), true, new CaseChanges.Claim(List.of(elsewhere), SCOPE));

        assertTrue(appended.isEmpty());
        assertEquals(null, punishmentOf(elsewhere));
    }

    @Test
    void aFileThatIsBoundAlreadyCannotBeClaimedAgain() {
        UUID file = givenStaged(SCOPE, OWNER);
        this.writer.appendMessage(message("m-1", "first"), true, new CaseChanges.Claim(List.of(file), SCOPE));

        List<CaseChanges.Appended> second = this.writer.appendMessage(message("m-2", "second"), true, new CaseChanges.Claim(List.of(file), SCOPE));

        assertTrue(second.isEmpty());
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'message.added'"));
    }

    @Test
    void aRepeatWithTheSameKeyClaimsNothingMore() {
        UUID first = givenStaged(SCOPE, OWNER);
        UUID waiting = givenStaged(SCOPE, OWNER);
        CaseEventDraft draft = message("m-1", "same");
        this.writer.appendMessage(draft, true, new CaseChanges.Claim(List.of(first), SCOPE));

        List<CaseChanges.Appended> repeat = this.writer.appendMessage(draft, true, new CaseChanges.Claim(List.of(waiting), SCOPE));

        assertTrue(repeat.isEmpty(), "the message exists already");
        assertEquals(null, punishmentOf(waiting), "a repeat must not swallow a file it did not send");
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'message.added'"));
    }

    @Test
    void aMessageWithoutFilesIsUnchanged() {
        List<CaseChanges.Appended> appended = this.writer.appendMessage(message("m-1", "plain"), true, null);

        assertEquals("message.added", appended.getFirst().type());
    }

    @Test
    void aWaitingCaseStillMovesToInReviewWhenTheMessageHasFiles() {
        this.db.execute("UPDATE punishment_case SET status = 'WAITING' WHERE punishment_identifier = ?", ID);
        UUID file = givenStaged(SCOPE, OWNER);

        List<CaseChanges.Appended> appended = this.writer.appendMessage(message("m-1", "x"), true, new CaseChanges.Claim(List.of(file), SCOPE));

        assertEquals(List.of("message.added", "case.status_changed"), appended.stream().map(CaseChanges.Appended::type).toList());
        assertEquals("IN_REVIEW", this.db.text("SELECT status FROM punishment_case WHERE punishment_identifier = ?", ID));
    }

    @Test
    void theMigrationMakesEveryExistingEvidenceItemStaffEvidence() {
        CaseDatabase fresh = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("19");
        fresh.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, "
                + "meta_data, ban_id_hash, ban_id_enc) VALUES ('old-1', ?, ?, ?, 'network', false, false, 1, 'BBBBBBB2', '{}'::jsonb, ?, ?)",
                STAFF, STAFF, OWNER, "old-1".getBytes(), new byte[]{1});
        fresh.execute("INSERT INTO evidence_items (identifier, punishment_identifier, owner, kind, source_system, created_at) "
                + "VALUES (?, 'old-1', ?, 3, 'old', 1)", UUID.randomUUID(), OWNER);

        fresh.migrate("latest");

        assertEquals(1, fresh.count("SELECT count(*) FROM evidence_items WHERE provenance = 0"), "STAFF is the default for what existed before");
        assertEquals(0, fresh.count("SELECT count(*) FROM evidence_items WHERE provenance <> 0"));
    }
}
