package net.onelitefeather.blackhole.backend.evidence.service;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.evidence.EvidenceFixtures;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceEventsTest {

    /** A publisher whose broker is down. */
    private static final class FailingPublisher extends DomainEventPublisher {

        FailingPublisher() {
            super(null);
        }

        @Override
        public void publish(String eventType, Map<String, Object> payload) {
            throw new IllegalStateException("broker down");
        }
    }

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
    }

    private static EvidenceDTO.CreateRequest link(String punishment, String owner, String correlation) {
        return new EvidenceDTO.CreateRequest(punishment, owner, correlation, EvidenceKind.LINK, "anticheat-x", null,
                "https://example.org/secret-path", Map.of("description", "secret-description"), null);
    }

    @Test
    void attachingPublishesAnAttachedEventForAPendingItem() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, this.bed.service.attach(link(null, OWNER, "c1"), ACTOR));

        RecordingEventPublisher.Event event = this.bed.events.eventsOfType("evidence.attached").getFirst();
        assertEquals(response.identifier().toString(), event.payload().get("evidenceId"));
        assertNull(event.payload().get("punishmentId"), "a pending item has no punishment");
        assertEquals(OWNER, event.payload().get("owner"));
        assertEquals("LINK", event.payload().get("kind"));
        assertEquals("anticheat-x", event.payload().get("sourceSystem"));
    }

    @Test
    void attachingToAPunishmentNamesThePunishmentInTheEvent() {
        PunishmentEntity punishment = this.bed.givenPunishment("p-1");

        this.bed.service.attach(link("p-1", null, null), ACTOR);

        assertEquals(punishment.getIdentifier(), this.bed.events.eventsOfType("evidence.attached").getFirst().payload().get("punishmentId"));
    }

    @Test
    void eventPayloadsCarryNoEvidenceContent() {
        this.bed.service.attach(link(null, OWNER, "c1"), ACTOR);

        Map<String, Object> payload = this.bed.events.eventsOfType("evidence.attached").getFirst().payload();

        assertEquals(Set.of("evidenceId", "punishmentId", "owner", "kind", "sourceSystem"), payload.keySet());
        assertTrue(!payload.toString().contains("secret"), payload.toString());
    }

    @Test
    void anUploadPublishesAnAttachedEventOfKindFile() throws IOException {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(null, OWNER, "c1", "test", null, null, null, "a.png");

        this.bed.service.attachFile(request, new ByteArrayInputStream(EvidenceFixtures.png(8, 8)), ACTOR);

        assertEquals("FILE", this.bed.events.eventsOfType("evidence.attached").getFirst().payload().get("kind"));
    }

    @Test
    void aRejectedAttachPublishesNothing() {
        this.bed.service.attach(link(null, null, null), ACTOR);

        assertTrue(this.bed.events.events().isEmpty());
    }

    @Test
    void deletingOneItemPublishesADeletedEventWithReasonManual() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, this.bed.service.attach(link(null, OWNER, "c1"), ACTOR));

        this.bed.service.delete(response.identifier(), "OTHER", null, ACTOR);

        Map<String, Object> payload = this.bed.events.eventsOfType("evidence.deleted").getFirst().payload();
        assertEquals("MANUAL", payload.get("reason"));
        assertEquals(response.identifier().toString(), payload.get("evidenceId"));
        assertEquals(Set.of("evidenceId", "punishmentId", "owner", "kind", "sourceSystem", "reason"), payload.keySet());
    }

    @Test
    void erasingAnOwnerPublishesOneDeletedEventPerItemWithReasonErasure() {
        this.bed.service.attach(link(null, OWNER, "c1"), ACTOR);
        this.bed.service.attach(link(null, OWNER, "c2"), ACTOR);

        this.bed.service.deleteByOwner(OWNER, "ERASURE_REQUEST", null, ACTOR);

        var deleted = this.bed.events.eventsOfType("evidence.deleted");
        assertEquals(2, deleted.size());
        assertTrue(deleted.stream().allMatch(event -> "ERASURE".equals(event.payload().get("reason"))));
    }

    @Test
    void aRejectedDeletePublishesNothing() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, this.bed.service.attach(link(null, OWNER, "c1"), ACTOR));

        this.bed.service.delete(response.identifier(), null, null, ACTOR);

        assertTrue(this.bed.events.eventsOfType("evidence.deleted").isEmpty());
    }

    @Test
    void aBrokerFailureDoesNotFailAnAttach() {
        EvidenceTestBed failing = new EvidenceTestBed(this.spool, new FailingPublisher());

        EvidenceDTO result = failing.service.attach(link(null, OWNER, "c1"), ACTOR);

        assertInstanceOf(EvidenceDTO.Response.class, result);
        assertEquals(1, failing.items.all().size(), "the item is stored");
    }

    @Test
    void aBrokerFailureDoesNotFailADelete() {
        EvidenceTestBed failing = new EvidenceTestBed(this.spool, new FailingPublisher());
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, failing.service.attach(link(null, OWNER, "c1"), ACTOR));

        EvidenceDTO result = failing.service.delete(response.identifier(), "OTHER", null, ACTOR);

        assertInstanceOf(EvidenceDTO.DeleteResponse.class, result);
        assertTrue(failing.items.all().isEmpty(), "the item is deleted");
    }
}
