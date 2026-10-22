package net.onelitefeather.blackhole.backend.evidence.service;

import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceCascadeTest {

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
        this.bed.givenPunishment("p-1");
        this.bed.givenPunishment("p-2");
    }

    private EvidenceItemEntity givenItem(String punishment, String fileKey) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), punishment, OWNER, null, EvidenceKind.LINK, "test", this.bed.clock.millis());
        if (fileKey != null) {
            item.setFile(fileKey, "shot.png", "image/png", 3L, "f".repeat(64));
            this.bed.blobs.given(fileKey, new byte[]{1, 2, 3});
        }
        this.bed.items.given(item, item.getIdentifier());
        return item;
    }

    @Test
    void deletingAPunishmentsEvidenceRemovesItsRowsAndFiles() {
        givenItem("p-1", "key-1");
        givenItem("p-1", null);

        int deleted = this.bed.service.deleteForPunishment("p-1");

        assertEquals(2, deleted, "both items were deleted");
        assertTrue(this.bed.items.all().isEmpty(), "no row is left");
        assertTrue(this.bed.blobs.keys().isEmpty(), "no file is left");
    }

    @Test
    void anotherPunishmentsEvidenceStays() {
        givenItem("p-1", "key-1");
        EvidenceItemEntity other = givenItem("p-2", "key-2");

        this.bed.service.deleteForPunishment("p-1");

        assertEquals(List.of(other), this.bed.items.all(), "the other punishment keeps its row");
        assertEquals(java.util.Set.of("key-2"), this.bed.blobs.keys(), "and its file");
    }

    @Test
    void theDeletionIsAuditedAndPublishedWithReasonCascade() {
        EvidenceItemEntity item = givenItem("p-1", "key-1");

        this.bed.service.deleteForPunishment("p-1");

        EvidenceAuditEntity entry = this.bed.audit.all().getFirst();
        assertEquals(EvidenceAuditAction.DELETED, entry.getAction());
        assertEquals(item.getIdentifier(), entry.getEvidenceIdentifier(), "the audit entry survives the evidence");
        assertEquals("CASCADE", entry.getReason());
        var event = this.bed.events.eventsOfType("evidence.deleted").getFirst();
        assertEquals("CASCADE", event.payload().get("reason"));
    }

    @Test
    void aFileThatCannotBeDeletedDoesNotKeepTheRow() {
        givenItem("p-1", "key-1");
        this.bed.blobs.fail();

        int deleted = this.bed.service.deleteForPunishment("p-1");

        assertEquals(1, deleted, "the item is deleted");
        assertTrue(this.bed.items.all().isEmpty(), "the row goes; the housekeeping sweep removes the file later");
        assertEquals(java.util.Set.of("key-1"), this.bed.blobs.keys(), "the file is still there");
    }

    @Test
    void aPunishmentWithoutEvidenceDeletesNothing() {
        assertEquals(0, this.bed.service.deleteForPunishment("p-1"), "nothing to delete");
    }
}
