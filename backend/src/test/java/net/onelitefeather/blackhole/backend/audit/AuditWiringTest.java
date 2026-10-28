package net.onelitefeather.blackhole.backend.audit;

import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.ACTOR;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.PROXY;
import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The staff writes of kicks, punishments and staff links each leave exactly one audit entry when they succeed. */
class AuditWiringTest {

    private final PunishmentTestBed bed = new PunishmentTestBed();

    @Test
    void aKickRecordsOneKickCreateEntryForTheTargetedPlayer() {
        KickDTO result = this.bed.kickService.kick(new KickDTO.CreateRequest(OWNER, null, null, "spamming"), "key-1", ACTOR);

        assertInstanceOf(KickDTO.Accepted.class, result, "the kick was accepted");
        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry");
        StaffAuditEntry row = this.bed.auditStore.rows().getFirst();
        assertEquals("kick.create", row.action());
        assertEquals("PLAYER", row.targetKind());
        assertEquals(OWNER, row.targetRef());
        assertEquals(ACTOR.oid(), row.actorOid());
    }

    @Test
    void aRefusedKickRecordsNothing() {
        this.bed.kickService.kick(new KickDTO.CreateRequest(OWNER, KickScope.SECTION, "no-such-section", "spamming"), "key-2", ACTOR);

        assertTrue(this.bed.auditStore.rows().isEmpty(), "a refused kick is not audited");
    }

    @Test
    void aRevokeRecordsOnePunishmentRevokeEntryForTheCase() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);

        this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        assertEquals(1, this.bed.auditStore.rows().size(), "only the revoke is audited, not the apply");
        StaffAuditEntry row = this.bed.auditStore.rows().getFirst();
        assertEquals("punishment.revoke", row.action());
        assertEquals("CASE", row.targetKind());
        assertTrue(row.targetRef() != null && row.targetRef().length() >= 8, "the case short id is the target");
    }

    @Test
    void aFailingAuditInsertDoesNotUndoTheRevoke() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), ACTOR);
        this.bed.auditStore.failInserts(true);

        PunishmentDTO result = this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", null), ACTOR);

        assertInstanceOf(PunishmentDTO.RevokeResponse.class, result, "the revoke still succeeds");
        assertTrue(this.bed.auditStore.rows().isEmpty(), "the failed insert left no row");
    }

    @Test
    void aProxyVouchingForStaffRecordsInGameWithTheStaffAsActor() {
        this.bed.givenTemplate("spam", "chat", null, step("PT1H", 50));
        UUID inGameStaff = UUID.nameUUIDFromBytes("in-game-staff".getBytes());
        this.bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(OWNER, "spam", null, null), PROXY);

        this.bed.punishmentService.revoke(new PunishmentDTO.RevokeRequest(OWNER, "chat", inGameStaff), PROXY);

        StaffAuditEntry row = this.bed.auditStore.rows().getFirst();
        assertEquals(Surface.IN_GAME, row.surface());
        assertEquals(inGameStaff, row.actorOid());
        assertEquals(PROXY.oid(), row.recordedByOid());
    }

    @Test
    void anAdminUnlinkRecordsOneStaffLinkUnlinkEntry() {
        UUID staff = UUID.nameUUIDFromBytes("linked-staff".getBytes());
        this.bed.staffLinks.givenLink(staff, UUID.nameUUIDFromBytes("minecraft".getBytes()));
        Actor admin = new Actor(UUID.nameUUIDFromBytes("admin".getBytes()), "Admin", ActorKind.HUMAN, false, true).withPanel(true);

        this.bed.staffLinks.service.unlink(staff, admin);

        assertEquals(1, this.bed.auditStore.rows().size(), "exactly one audit entry");
        StaffAuditEntry row = this.bed.auditStore.rows().getFirst();
        assertEquals("staff-link.unlink", row.action());
        assertEquals(Surface.PANEL, row.surface());
        assertEquals(admin.oid(), row.actorOid());
    }
}
