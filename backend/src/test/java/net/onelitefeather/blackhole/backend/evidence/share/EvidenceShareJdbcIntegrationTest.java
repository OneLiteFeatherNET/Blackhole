package net.onelitefeather.blackhole.backend.evidence.share;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.JdbcCaseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The share statements against the real schema (V23): the unique active share, row and event together, revoke once, the cascades. */
@Tag("integration")
class EvidenceShareJdbcIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String ID = "share-1";
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID APPEAL = UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000a1");
    private static final CaseActor ACTOR = new CaseActor(ActorTrust.VERIFIED, STAFF, STAFF, null);

    private CaseDatabase db;
    private JdbcEvidenceShareStore store;
    private final UUID evidence = UUID.randomUUID();

    @BeforeEach
    void givenAMigratedSchema() {
        this.db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
        this.store = new JdbcEvidenceShareStore(this.db.dataSource);
        this.db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, owner, section_key, permanent, lifted, created_at, short_id, "
                + "meta_data, ban_id_hash, ban_id_enc) VALUES (?, ?, ?, ?, 'network', false, false, 1, 'AAAAAAA1', '{}'::jsonb, ?, ?)",
                ID, STAFF, STAFF, OWNER, ID.getBytes(), new byte[]{1});
        new JdbcCaseWriter(this.db.dataSource).ensureCase(ID, 10L);
        givenAppeal(APPEAL, 0, 100L);
    }

    private void givenAppeal(UUID id, int status, long createdAt) {
        this.db.execute("INSERT INTO appeals (identifier, created_at, updated_at, status, punishment_identifier) VALUES (?, ?, ?, ?, ?)",
                id, createdAt, createdAt, status, ID);
    }

    private EvidenceShareStore.Share share(UUID shareId, List<Integer> selection) {
        return new EvidenceShareStore.Share(shareId, ID, APPEAL, this.evidence, selection.isEmpty() ? "FILE" : "EXCERPT", selection,
                selection.isEmpty() ? null : "Alice", true, STAFF, 50L, null, null, null);
    }

    private CaseEventDraft shared(UUID shareId, int lines) {
        return new CaseEventDraft(ID, new CaseEventPayload.EvidenceShared(shareId.toString(), "CHAT_LOG", "EXCERPT", lines), 50L,
                CaseEventOrigin.LIVE, ACTOR, shareId.toString(), "evidence.shared:" + shareId, CaseEventDraft.PLAYER);
    }

    private CaseEventDraft revoked(UUID shareId) {
        return new CaseEventDraft(ID, new CaseEventPayload.EvidenceShareRevoked(shareId.toString(), "PRIVACY"), 60L, CaseEventOrigin.LIVE,
                ACTOR, shareId.toString(), "evidence.share_revoked:" + shareId, CaseEventDraft.PLAYER);
    }

    @Test
    void aShareAndItsEventAreStoredTogether() {
        UUID shareId = UUID.randomUUID();

        EvidenceShareStore.Created created = this.store.create(share(shareId, List.of(0, 2, 5)), shared(shareId, 3));

        assertFalse(created.repeated());
        assertNotNull(created.event());
        assertEquals("evidence.shared", created.event().type());
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'evidence.shared' AND visibility = 'PLAYER'"));
        EvidenceShareStore.Share read = this.store.find(shareId, ID).orElseThrow();
        assertEquals(List.of(0, 2, 5), read.selection(), "line indexes only");
        assertEquals("Alice", read.appellantSpeaker());
        assertTrue(read.confirmedMinimised());
        assertEquals(STAFF, read.sharedByOid());
        assertTrue(read.active());
    }

    @Test
    void theSameItemAndSelectionWhileActiveIsTheSameShare() {
        UUID first = UUID.randomUUID();
        this.store.create(share(first, List.of(1, 2)), shared(first, 2));
        UUID second = UUID.randomUUID();

        EvidenceShareStore.Created repeat = this.store.create(share(second, List.of(1, 2)), shared(second, 2));

        assertTrue(repeat.repeated());
        assertEquals(first, repeat.share().shareId(), "the existing share is returned");
        assertEquals(1, this.db.count("SELECT count(*) FROM evidence_share"));
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'evidence.shared'"), "no second event");
    }

    @Test
    void aDifferentSelectionOrItemIsAnotherShare() {
        UUID first = UUID.randomUUID();
        this.store.create(share(first, List.of(1, 2)), shared(first, 2));
        UUID second = UUID.randomUUID();

        assertFalse(this.store.create(share(second, List.of(1, 2, 3)), shared(second, 3)).repeated());
        assertEquals(2, this.db.count("SELECT count(*) FROM evidence_share"));
    }

    @Test
    void aRevokedShareMakesRoomForTheSameSelectionAgain() {
        UUID first = UUID.randomUUID();
        this.store.create(share(first, List.of(4)), shared(first, 1));
        this.store.revoke(first, ID, 60L, STAFF, "MISTAKE", revoked(first));
        UUID second = UUID.randomUUID();

        EvidenceShareStore.Created again = this.store.create(share(second, List.of(4)), shared(second, 1));

        assertFalse(again.repeated());
        assertEquals(2, this.db.count("SELECT count(*) FROM evidence_share"));
    }

    @Test
    void revokingMarksTheRowAndAppendsOneEvent() {
        UUID shareId = UUID.randomUUID();
        this.store.create(share(shareId, List.of(4)), shared(shareId, 1));

        Optional<CaseChanges.Appended> event = this.store.revoke(shareId, ID, 60L, STAFF, "PRIVACY", revoked(shareId));

        assertTrue(event.isPresent());
        EvidenceShareStore.Share read = this.store.find(shareId, ID).orElseThrow();
        assertFalse(read.active());
        assertEquals(60L, read.revokedAt());
        assertEquals(STAFF, read.revokedByOid());
        assertEquals("PRIVACY", read.revokeReason());
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'evidence.share_revoked'"));
    }

    @Test
    void revokingTwiceChangesNothing() {
        UUID shareId = UUID.randomUUID();
        this.store.create(share(shareId, List.of(4)), shared(shareId, 1));
        this.store.revoke(shareId, ID, 60L, STAFF, "PRIVACY", revoked(shareId));

        Optional<CaseChanges.Appended> second = this.store.revoke(shareId, ID, 70L, STAFF, "OTHER", revoked(shareId));

        assertTrue(second.isEmpty());
        assertEquals("PRIVACY", this.store.find(shareId, ID).orElseThrow().revokeReason(), "the first reason stands");
        assertEquals(1, this.db.count("SELECT count(*) FROM case_event WHERE type = 'evidence.share_revoked'"));
    }

    @Test
    void aShareOfAnotherCaseCannotBeRevokedThroughThisOne() {
        UUID shareId = UUID.randomUUID();
        this.store.create(share(shareId, List.of(4)), shared(shareId, 1));

        assertTrue(this.store.revoke(shareId, "other-case", 60L, STAFF, "OTHER", revoked(shareId)).isEmpty());
        assertTrue(this.store.find(shareId, "other-case").isEmpty());
        assertTrue(this.store.find(shareId, ID).orElseThrow().active());
    }

    @Test
    void theLatestAppealIgnoresAnIneligibleOne() {
        this.db.execute("UPDATE appeals SET status = 5 WHERE identifier = ?", APPEAL);
        givenAppeal(UUID.randomUUID(), 6, 200L);

        assertEquals(Optional.of(APPEAL), this.store.latestAppeal(ID), "status 6 (ineligible) is not an appeal to talk in");
        givenAppeal(UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000a2"), 1, 300L);
        assertEquals(Optional.of(UUID.fromString("a1b2c3d4-0000-4000-8000-0000000000a2")), this.store.latestAppeal(ID));
    }

    @Test
    void theSharesGoWithThePunishment() {
        UUID shareId = UUID.randomUUID();
        this.store.create(share(shareId, List.of(4)), shared(shareId, 1));

        this.db.execute("DELETE FROM case_event WHERE punishment_identifier = ?", ID);
        this.db.execute("DELETE FROM punishment_case WHERE punishment_identifier = ?", ID);
        this.db.execute("DELETE FROM appeals WHERE punishment_identifier = ?", ID);
        this.db.execute("DELETE FROM punishments WHERE identifier = ?", ID);

        assertEquals(0, this.db.count("SELECT count(*) FROM evidence_share"), "no share outlives its punishment");
    }

    @Test
    void theShareOutlivesTheEvidenceRowSoItCanReadAsUnavailable() {
        UUID shareId = UUID.randomUUID();
        this.store.create(share(shareId, List.of(4)), shared(shareId, 1));

        assertEquals(1, this.db.count("SELECT count(*) FROM evidence_share WHERE evidence_id = ?", this.evidence),
                "there is no evidence row and no foreign key: the reader renders UNAVAILABLE");
    }
}
