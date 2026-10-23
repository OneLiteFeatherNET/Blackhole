package net.onelitefeather.blackhole.backend.evidence.service;

import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvidenceLifecycleTest {

    private static final UUID APPEAL = UUID.nameUUIDFromBytes("appeal".getBytes());
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final Attribution BY = new Attribution(STAFF, STAFF);

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
        this.bed.givenPunishment("p-1");
        this.bed.givenPunishment("p-2");
    }

    private EvidenceItemEntity givenItem(String punishment) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), punishment, OWNER, null, EvidenceKind.LINK, "test", this.bed.clock.millis());
        this.bed.items.given(item, item.getIdentifier());
        return item;
    }

    @Test
    void aFullLiftMarksTheEvidenceLiftedWithTheTimeAndTheAppeal() {
        EvidenceItemEntity item = givenItem("p-1");
        this.bed.clock.advance(Duration.ofMinutes(5));

        int changed = this.bed.service.markLifted("p-1", APPEAL, BY);

        assertEquals(1, changed, "one item changed");
        assertEquals(EvidenceStatus.LIFTED, item.getStatus());
        assertEquals(this.bed.clock.millis(), item.getStatusChangedAt(), "the change is stamped with the clock");
        assertEquals(APPEAL, item.getLiftedByAppeal());
    }

    @Test
    void aRevokeMarksTheEvidenceRevokedWithoutAnAppeal() {
        EvidenceItemEntity item = givenItem("p-1");
        this.bed.clock.advance(Duration.ofMinutes(7));

        int changed = this.bed.service.markRevoked("p-1", BY);

        assertEquals(1, changed, "one item changed");
        assertEquals(EvidenceStatus.REVOKED, item.getStatus());
        assertEquals(this.bed.clock.millis(), item.getStatusChangedAt(), "the change is stamped with the clock");
        assertNull(item.getLiftedByAppeal(), "no appeal lifted a revoked punishment");
    }

    @Test
    void aLifecycleChangeKeepsTheEvidence() {
        givenItem("p-1");

        this.bed.service.markRevoked("p-1", BY);

        assertEquals(1, this.bed.items.all().size(), "the row is kept");
        assertEquals(0, this.bed.events.eventsOfType("evidence.deleted").size(), "nothing was deleted");
    }

    @Test
    void anItemThatIsAlreadyInactiveKeepsItsFirstStatus() {
        EvidenceItemEntity item = givenItem("p-1");
        item.markStatus(EvidenceStatus.REVOKED, 1L, null);

        int changed = this.bed.service.markLifted("p-1", APPEAL, BY);

        assertEquals(0, changed, "nothing left to change");
        assertEquals(EvidenceStatus.REVOKED, item.getStatus());
        assertEquals(1L, item.getStatusChangedAt());
    }

    @Test
    void anotherPunishmentsEvidenceIsNotTouched() {
        EvidenceItemEntity other = givenItem("p-2");
        givenItem("p-1");

        this.bed.service.markLifted("p-1", APPEAL, BY);

        assertEquals(EvidenceStatus.VALID, other.getStatus());
    }

    @Test
    void liftedEvidenceIsNoLongerCounted() {
        givenItem("p-1");
        givenItem("p-1");

        this.bed.service.markLifted("p-1", APPEAL, BY);

        assertEquals(0, this.bed.service.countValid("p-1"), "inactive evidence is not counted");
    }
}
