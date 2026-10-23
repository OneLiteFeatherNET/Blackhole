package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The evidence flows record attaching, lifting and revoking in the case, through the gateway, after their own write. */
class EvidenceRecordingTest {

    private static final UUID APPEAL = UUID.nameUUIDFromBytes("appeal".getBytes());
    private static final Attribution BY = new Attribution(ACTOR.oid(), ACTOR.oid());

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
        this.bed.givenPunishment("p-1");
    }

    private EvidenceDTO.Response attach(String punishment, String correlation) {
        EvidenceDTO result = this.bed.service.attach(new EvidenceDTO.CreateRequest(punishment, punishment == null ? OWNER : null, correlation,
                EvidenceKind.LINK, "test-system", 5L, "https://example.org/replay/1", Map.of("description", "secret content"), null), ACTOR);
        return assertInstanceOf(EvidenceDTO.Response.class, result, () -> String.valueOf(result));
    }

    @Test
    void attachingEvidenceToAPunishmentRecordsIdsAndEnumsOnly() {
        EvidenceDTO.Response response = attach("p-1", null);

        CaseEventRecord event = this.bed.cases.log.ofType("p-1", "evidence.attached").getFirst();
        assertEquals(new CaseEventPayload.EvidenceAttached(response.identifier().toString(), "LINK", "test-system"), event.payload());
        assertEquals("evidence.attached:" + response.identifier(), event.dedupeKey());
        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals(ACTOR.oid(), event.actorOid());
        assertFalse(event.body().toString().contains("secret content") || event.body().toString().contains("example.org"),
                "no evidence content and no reference in the log");
    }

    @Test
    void evidenceWaitingForAPunishmentIsRecordedWhenThePunishmentClaimsIt() {
        EvidenceDTO.Response response = attach(null, "c1");
        assertTrue(this.bed.cases.log.all().isEmpty(), "a pending item belongs to no case yet");
        this.bed.clock.advance(Duration.ofSeconds(30));

        this.bed.service.claimPending(OWNER, "c1", "p-1");

        CaseEventRecord event = this.bed.cases.log.ofType("p-1", "evidence.attached").getFirst();
        assertEquals(response.identifier().toString(), event.subjectId());
        assertEquals(response.createdAt(), event.occurredAt(), "dated by when the evidence was sent, which is before the punishment");
        assertEquals(ACTOR.oid(), event.actorOid(), "the actor who attached it");
    }

    @Test
    void liftingEvidenceRecordsTheAppealAndTheReviewer() {
        EvidenceDTO.Response response = attach("p-1", null);

        this.bed.service.markLifted("p-1", APPEAL, BY);

        CaseEventRecord event = this.bed.cases.log.ofType("p-1", "evidence.lifted").getFirst();
        assertEquals(new CaseEventPayload.EvidenceLifted(response.identifier().toString(), APPEAL.toString(), false), event.payload());
        assertEquals(ACTOR.oid(), event.actorOid());
    }

    @Test
    void revokingEvidenceRecordsTheRevoke() {
        EvidenceDTO.Response response = attach("p-1", null);

        this.bed.service.markRevoked("p-1", BY);

        assertEquals(new CaseEventPayload.EvidenceRevoked(response.identifier().toString(), false),
                this.bed.cases.log.ofType("p-1", "evidence.revoked").getFirst().payload());
    }

    @Test
    void anItemThatIsAlreadyInactiveRecordsNoSecondChange() {
        attach("p-1", null);
        this.bed.service.markRevoked("p-1", BY);

        this.bed.service.markLifted("p-1", APPEAL, BY);

        assertTrue(this.bed.cases.log.ofType("p-1", "evidence.lifted").isEmpty(), "nothing changed, so nothing is recorded");
    }

    @Test
    void aFailingRecordingDoesNotFailAttachingEvidence() {
        this.bed.cases.log.failAppends(new IllegalStateException("database down"));

        EvidenceDTO.Response response = attach("p-1", null);

        assertEquals(1, this.bed.items.all().size(), "the evidence is stored: " + response.identifier());
        assertEquals(1.0, this.bed.cases.failures(), "the failure is counted");
    }

    @Test
    void aFailingRecordingDoesNotFailTheLifecycleChange() {
        attach("p-1", null);
        this.bed.cases.log.failAppends(new IllegalStateException("database down"));

        int changed = this.bed.service.markRevoked("p-1", BY);

        assertEquals(1, changed, "the evidence is revoked regardless");
    }
}
