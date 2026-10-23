package net.onelitefeather.blackhole.backend.evidence.service;

import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditAction;
import net.onelitefeather.blackhole.backend.evidence.EvidenceAuditEntity;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed.PROXY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who evidence is attributed to and which verified principal recorded it, in the item and in its audit trail. */
class EvidenceVerifiedActorTest {

    private static final UUID INGAME_STAFF = UUID.nameUUIDFromBytes("in-game-staff".getBytes());

    @TempDir
    Path spool;

    private EvidenceTestBed bed;

    @BeforeEach
    void setUp() {
        this.bed = new EvidenceTestBed(this.spool);
    }

    private EvidenceDTO attach(UUID onBehalfOf, net.onelitefeather.blackhole.backend.security.Actor actor) {
        return this.bed.service.attach(new EvidenceDTO.CreateRequest(
                null, OWNER, "c1", EvidenceKind.LINK, "test", null, "https://example.org/a", null, onBehalfOf), actor);
    }

    @Test
    void evidenceAttachedByAStaffMemberIsAttributedToAndRecordedByTheirOid() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, attach(null, ACTOR));

        assertEquals(ACTOR.oid(), response.createdBy());
        assertEquals(ACTOR.oid(), response.recordedByOid());
        assertTrue(response.verified(), "attached with a token");
    }

    @Test
    void evidenceAttachedByTheProxyOnBehalfOfAStaffMemberKeepsBothIdentities() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, attach(INGAME_STAFF, PROXY));

        assertEquals(INGAME_STAFF, response.createdBy(), "attributed to the in-game staff UUID");
        assertEquals(PROXY.oid(), response.recordedByOid(), "recorded by the proxy's verified oid");
        EvidenceAuditEntity entry = this.bed.audit.all().getFirst();
        assertEquals(INGAME_STAFF, entry.getActor(), "audit actor is the delegated UUID");
        assertEquals(PROXY.oid(), entry.getRecordedByOid(), "audit records the proxy's oid");
    }

    @Test
    void aStaffMemberSendingOnBehalfOfIsRejectedAndNothingIsStored() {
        EvidenceDTO result = attach(INGAME_STAFF, ACTOR);

        assertEquals(EvidenceErrorKind.VALIDATION, assertInstanceOf(EvidenceDTO.Error.class, result).kind());
        assertTrue(this.bed.items.all().isEmpty(), "no evidence is stored");
        assertTrue(this.bed.audit.all().isEmpty(), "no audit line either");
    }

    @Test
    void aReadIsAuditedWithTheCallersOid() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, attach(null, PROXY));

        this.bed.service.get(response.identifier(), ACTOR);

        EvidenceAuditEntity viewed = this.bed.audit.all().stream()
                .filter(entry -> entry.getAction() == EvidenceAuditAction.VIEWED).findFirst().orElseThrow();
        assertEquals(ACTOR.oid(), viewed.getActor());
        assertEquals(ACTOR.oid(), viewed.getRecordedByOid());
    }

    @Test
    void anAdminDeleteIsAuditedWithTheAdminsOid() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, attach(null, ACTOR));

        this.bed.service.delete(response.identifier(), "OTHER", null, ACTOR);

        EvidenceAuditEntity deleted = this.bed.audit.all().stream()
                .filter(entry -> entry.getAction() == EvidenceAuditAction.DELETED).findFirst().orElseThrow();
        assertEquals(ACTOR.oid(), deleted.getRecordedByOid());
    }

    @Test
    void listingInactiveEvidenceRecordsTheVerifiedCaller() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, attach(null, ACTOR));
        this.bed.items.all().forEach(item -> item.markStatus(net.onelitefeather.blackhole.backend.evidence.EvidenceStatus.REVOKED, 9L, null));

        this.bed.service.listByOwner(OWNER, true, Pageable.from(0, 20), PROXY);

        EvidenceAuditEntity entry = this.bed.audit.all().stream()
                .filter(e -> e.getAction() == EvidenceAuditAction.VIEWED_INACTIVE).findFirst().orElseThrow();
        assertEquals(PROXY.oid(), entry.getRecordedByOid(), "the caller who revealed the item: " + response.identifier());
    }

    @Test
    void aSystemActionLeavesNoVerifiedPrincipal() {
        EvidenceDTO.Response response = assertInstanceOf(EvidenceDTO.Response.class, attach(null, ACTOR));
        this.bed.items.all().getFirst();

        this.bed.service.deleteUnclaimedCreatedBefore(Long.MAX_VALUE);

        EvidenceAuditEntity swept = this.bed.audit.all().stream()
                .filter(entry -> entry.getAction() == EvidenceAuditAction.DELETED).findFirst().orElseThrow();
        assertEquals(null, swept.getRecordedByOid(), "nobody signed in for the housekeeping sweep: " + response.identifier());
    }
}
