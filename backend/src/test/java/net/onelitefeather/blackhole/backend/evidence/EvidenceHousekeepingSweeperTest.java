package net.onelitefeather.blackhole.backend.evidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceHousekeepingSweeperTest {

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
        this.bed.givenPunishment("p-1");
    }

    /** An item created now; pending unless a punishment is given. */
    private EvidenceItemEntity givenItem(String punishment, String fileKey) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), punishment, OWNER, punishment == null ? "corr" : null,
                EvidenceKind.LINK, "test", this.bed.clock.millis());
        if (fileKey != null) {
            item.setFile(fileKey, "shot.png", "image/png", 3L, "f".repeat(64));
            this.bed.blobs.given(fileKey, new byte[]{1, 2, 3}, this.bed.clock.instant());
        }
        this.bed.items.given(item, item.getIdentifier());
        return item;
    }

    @Test
    void pendingEvidenceOlderThanTheTtlIsDeletedWithItsFile() {
        givenItem(null, "key-1");
        this.bed.clock.advance(this.bed.config.getPendingTtl().plusSeconds(1));

        this.bed.sweeper.sweep();

        assertTrue(this.bed.items.all().isEmpty(), "the unclaimed row is gone");
        assertTrue(this.bed.blobs.keys().isEmpty(), "and its file");
        assertEquals("UNCLAIMED_EXPIRED", this.bed.events.eventsOfType("evidence.deleted").getFirst().payload().get("reason"));
    }

    @Test
    void pendingEvidenceYoungerThanTheTtlIsKept() {
        givenItem(null, null);
        this.bed.clock.advance(this.bed.config.getPendingTtl().minusSeconds(1));

        this.bed.sweeper.sweep();

        assertEquals(1, this.bed.items.all().size(), "the evidence may still be claimed");
    }

    private EvidenceItemEntity givenReportItem() {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), null, OWNER, "report:" + UUID.randomUUID(),
                EvidenceKind.CHAT_LOG, "test", this.bed.clock.millis());
        this.bed.items.given(item, item.getIdentifier());
        return item;
    }

    @Test
    void pendingEvidenceOfAReportOutlivesTheNormalTtl() {
        givenReportItem();
        this.bed.clock.advance(this.bed.config.getPendingTtl().plusSeconds(1));

        this.bed.sweeper.sweep();

        assertEquals(1, this.bed.items.all().size(), "an open report may still lead to a punishment that claims it");
    }

    @Test
    void pendingEvidenceOfAReportExpiresAfterItsOwnTtl() {
        givenReportItem();
        this.bed.clock.advance(this.bed.config.getReportPendingTtl().plusSeconds(1));

        this.bed.sweeper.sweep();

        assertTrue(this.bed.items.all().isEmpty(), "evidence of a report that never led to a punishment is dropped eventually");
    }

    @Test
    void claimedEvidenceIsNeverDeletedHoweverOld() {
        givenItem("p-1", "key-1");
        this.bed.clock.advance(Duration.ofDays(3650));

        this.bed.sweeper.sweep();

        assertEquals(1, this.bed.items.all().size(), "the row stays");
        assertEquals(Set.of("key-1"), this.bed.blobs.keys(), "and its file");
    }

    @Test
    void aFileWithoutARowOlderThanADayIsRemoved() {
        this.bed.blobs.given("orphan", new byte[]{1}, this.bed.clock.instant());
        this.bed.clock.advance(Duration.ofDays(1).plusSeconds(1));

        this.bed.sweeper.sweep();

        assertTrue(this.bed.blobs.keys().isEmpty(), "the orphan is gone");
    }

    @Test
    void aFileWithoutARowYoungerThanADayIsKept() {
        this.bed.blobs.given("fresh", new byte[]{1}, this.bed.clock.instant());
        this.bed.clock.advance(Duration.ofDays(1).minusSeconds(1));

        this.bed.sweeper.sweep();

        assertEquals(Set.of("fresh"), this.bed.blobs.keys(), "an upload may still be saving its row");
    }

    @Test
    void aFileWithARowIsNotAnOrphan() {
        givenItem("p-1", "key-1");
        this.bed.clock.advance(Duration.ofDays(30));

        this.bed.sweeper.sweep();

        assertEquals(Set.of("key-1"), this.bed.blobs.keys());
    }

    @Test
    void auditRowsAreKeptWhenNoRetentionIsConfigured() {
        this.bed.audit.given(new EvidenceAuditEntity(UUID.randomUUID(), UUID.randomUUID(), null, OWNER,
                EvidenceAuditAction.ATTACHED, null, null, null, null, this.bed.clock.millis()), UUID.randomUUID());
        this.bed.clock.advance(Duration.ofDays(3650));

        this.bed.sweeper.sweep();

        assertEquals(1, this.bed.audit.all().size(), "audit rows are kept indefinitely by default");
    }

    @Test
    void auditRowsOlderThanTheConfiguredRetentionArePurged() {
        this.bed.config.setAuditRetention(Duration.ofDays(30));
        UUID old = UUID.randomUUID();
        this.bed.audit.given(new EvidenceAuditEntity(old, UUID.randomUUID(), null, OWNER,
                EvidenceAuditAction.ATTACHED, null, null, null, null, this.bed.clock.millis()), old);
        this.bed.clock.advance(Duration.ofDays(31));
        UUID recent = UUID.randomUUID();
        this.bed.audit.given(new EvidenceAuditEntity(recent, UUID.randomUUID(), null, OWNER,
                EvidenceAuditAction.ATTACHED, null, null, null, null, this.bed.clock.millis()), recent);

        this.bed.sweeper.sweep();

        assertEquals(1, this.bed.audit.all().size(), "only the recent entry is left");
        assertEquals(recent, this.bed.audit.all().getFirst().getIdentifier());
    }

    @Test
    void anUnavailableStoreDoesNotStopTheRestOfTheSweep() {
        givenItem(null, null);
        this.bed.blobs.fail();
        this.bed.clock.advance(this.bed.config.getPendingTtl().plusSeconds(1));

        this.bed.sweeper.sweep();

        assertTrue(this.bed.items.all().isEmpty(), "the unclaimed row is still deleted");
    }
}
