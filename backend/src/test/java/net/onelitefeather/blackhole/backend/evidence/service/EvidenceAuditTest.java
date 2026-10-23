package net.onelitefeather.blackhole.backend.evidence.service;

import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAuditTest {

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
    }

    private EvidenceDTO.Response attachLink(String correlation) {
        return assertInstanceOf(EvidenceDTO.Response.class, this.bed.service.attach(new EvidenceDTO.CreateRequest(
                null, OWNER, correlation, EvidenceKind.LINK, "test", null, "https://example.org/a", null, null), ACTOR));
    }

    private List<EvidenceAuditEntity> entries(EvidenceAuditAction action) {
        return this.bed.audit.all().stream().filter(entry -> entry.getAction() == action).toList();
    }

    private void markInactive(EvidenceDTO.Response response, EvidenceStatus status) {
        this.bed.items.all().stream().filter(item -> item.getIdentifier().equals(response.identifier())).forEach(item -> item.markStatus(status, 9L, null));
    }

    @Test
    void attachingWritesAnAttachedEntryWithTheSuppliedActor() {
        EvidenceDTO.Response response = attachLink("c1");

        EvidenceAuditEntity entry = entries(EvidenceAuditAction.ATTACHED).getFirst();
        assertEquals(response.identifier(), entry.getEvidenceIdentifier());
        assertEquals(STAFF, entry.getActor());
        assertEquals(OWNER, entry.getOwner());
        assertEquals(this.bed.clock.millis(), entry.getOccurredAt());
        assertNull(entry.getPunishmentIdentifier(), "pending evidence has no punishment yet");
    }

    @Test
    void anUploadWritesAnAttachedEntryToo() throws IOException {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, OWNER, "c1", "test", null, null, null, "a.png");

        this.bed.service.attachFile(request, new ByteArrayInputStream(EvidenceFixtures.png(8, 8)), ACTOR);

        assertEquals(1, entries(EvidenceAuditAction.ATTACHED).size());
    }

    @Test
    void readingAValidItemWritesAViewedEntry() {
        EvidenceDTO.Response response = attachLink("c1");

        this.bed.service.get(response.identifier(), ACTOR);

        assertEquals(1, entries(EvidenceAuditAction.VIEWED).size());
        assertEquals(0, entries(EvidenceAuditAction.VIEWED_INACTIVE).size());
    }

    @Test
    void readingALiftedItemWritesAViewedInactiveEntry() {
        EvidenceDTO.Response response = attachLink("c1");
        markInactive(response, EvidenceStatus.LIFTED);

        this.bed.service.get(response.identifier(), ACTOR);

        assertEquals(1, entries(EvidenceAuditAction.VIEWED_INACTIVE).size());
    }

    @Test
    void listingInactiveItemsWritesOneViewedInactiveEntryPerRevealedItem() {
        EvidenceDTO.Response lifted = attachLink("c1");
        EvidenceDTO.Response revoked = attachLink("c2");
        attachLink("c3");
        markInactive(lifted, EvidenceStatus.LIFTED);
        markInactive(revoked, EvidenceStatus.REVOKED);

        this.bed.service.listByOwner(OWNER, true, Pageable.from(0, 20), ACTOR);

        assertEquals(2, entries(EvidenceAuditAction.VIEWED_INACTIVE).size());
        assertEquals(Set.of(lifted.identifier(), revoked.identifier()),
                Set.copyOf(entries(EvidenceAuditAction.VIEWED_INACTIVE).stream().map(EvidenceAuditEntity::getEvidenceIdentifier).toList()));
    }

    @Test
    void theDefaultListWritesNoAuditEntry() {
        attachLink("c1");
        int before = this.bed.audit.all().size();

        this.bed.service.listByOwner(OWNER, false, Pageable.from(0, 20), ACTOR);

        assertEquals(before, this.bed.audit.all().size());
    }

    @Test
    void downloadingAFileWritesADownloadedEntryWithTheActor() throws IOException {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, OWNER, "c1", "test", null, null, null, "a.png");
        EvidenceDTO.Response file = assertInstanceOf(EvidenceDTO.Response.class,
                this.bed.service.attachFile(request, new ByteArrayInputStream(EvidenceFixtures.png(8, 8)), ACTOR));

        this.bed.service.content(file.identifier(), null, ACTOR);

        EvidenceAuditEntity entry = entries(EvidenceAuditAction.DOWNLOADED).getFirst();
        assertEquals(STAFF, entry.getActor());
        assertEquals(file.identifier(), entry.getEvidenceIdentifier());
    }

    @Test
    void laterRangeRequestsOfTheSameDownloadAreNotAuditedAgain() throws IOException {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, OWNER, "c1", "test", null, null, null, "a.png");
        EvidenceDTO.Response file = assertInstanceOf(EvidenceDTO.Response.class,
                this.bed.service.attachFile(request, new ByteArrayInputStream(EvidenceFixtures.png(8, 8)), ACTOR));

        this.bed.service.content(file.identifier(), "bytes=0-3", ACTOR);
        this.bed.service.content(file.identifier(), "bytes=4-7", ACTOR);
        this.bed.service.content(file.identifier(), "bytes=8-11", ACTOR);

        assertEquals(1, entries(EvidenceAuditAction.DOWNLOADED).size(), "only the request that starts the file is audited");
    }

    @Test
    void deletingWritesADeletedEntryWithReasonNoteAndActor() {
        EvidenceDTO.Response response = attachLink("c1");

        this.bed.service.delete(response.identifier(), "wrong_evidence", "typo", ACTOR);

        EvidenceAuditEntity entry = entries(EvidenceAuditAction.DELETED).getFirst();
        assertEquals("WRONG_EVIDENCE", entry.getReason());
        assertEquals("typo", entry.getNote());
        assertEquals(STAFF, entry.getActor());
    }

    @Test
    void erasingAnOwnerWritesOneDeletedEntryPerItem() {
        attachLink("c1");
        attachLink("c2");

        this.bed.service.deleteByOwner(OWNER, "ERASURE_REQUEST", null, ACTOR);

        assertEquals(2, entries(EvidenceAuditAction.DELETED).size());
        assertTrue(entries(EvidenceAuditAction.DELETED).stream().allMatch(entry -> "ERASURE_REQUEST".equals(entry.getReason())));
    }

    @Test
    void aRejectedDeleteWritesNoDeletedEntry() {
        EvidenceDTO.Response response = attachLink("c1");

        this.bed.service.delete(response.identifier(), null, null, ACTOR);

        assertEquals(0, entries(EvidenceAuditAction.DELETED).size());
    }

    @Test
    void theAuditTrailOutlivesTheDeletedEvidence() {
        EvidenceDTO.Response response = attachLink("c1");

        this.bed.service.delete(response.identifier(), "OTHER", null, ACTOR);

        assertTrue(this.bed.items.all().isEmpty(), "the evidence is gone");
        assertEquals(1, entries(EvidenceAuditAction.ATTACHED).size(), "its trail is not");
        assertEquals(1, entries(EvidenceAuditAction.DELETED).size());
    }

    @Test
    void anAuditEntryHasNoFieldThatCouldHoldEvidenceContent() {
        Set<String> fields = Set.copyOf(Arrays.stream(EvidenceAuditEntity.class.getDeclaredFields()).map(Field::getName).toList());

        assertEquals(Set.of("identifier", "evidenceIdentifier", "punishmentIdentifier", "owner", "action", "actor", "recordedByOid", "reason", "note", "occurredAt"),
                fields, "content fields (payload, reference, file) must never be added to the audit entity");
    }

    @Test
    void anAuditEntryNeverContainsTheEvidenceText() {
        Map<String, Object> payload = Map.of("description", "secret-description-text");
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, this.bed.service.attach(new EvidenceDTO.CreateRequest(
                null, OWNER, "c9", EvidenceKind.LINK, "test", null, "https://example.org/secret-path", payload, null), ACTOR));
        this.bed.service.get(response.identifier(), ACTOR);

        String dump = this.bed.audit.all().stream().map(entry -> String.join("|", String.valueOf(entry.getReason()), String.valueOf(entry.getNote()),
                String.valueOf(entry.getOwner()), String.valueOf(entry.getPunishmentIdentifier()))).reduce("", String::concat);
        assertTrue(!dump.contains("secret"), dump);
    }
}
