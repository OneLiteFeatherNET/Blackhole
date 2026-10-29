package net.onelitefeather.blackhole.backend.exemption;

import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.kick.KickScope;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.punishment.StepKind;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishmentDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.support.PunishmentTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every punishment write path (manual, template, warning, template kick, manual kick) is checked against exemption. */
class ExemptionApplyTest {

    private static final UUID MOD_OID = UUID.nameUUIDFromBytes("mod".getBytes());
    private static final UUID ADMIN_OID = UUID.nameUUIDFromBytes("admin".getBytes());
    private static final UUID ADMIN_TARGET_OID = UUID.nameUUIDFromBytes("admin-target-staff".getBytes());
    private static final String ADMIN_TARGET = ExemptionService.ownerOf(UUID.nameUUIDFromBytes("admin-target".getBytes()));
    private static final UUID PROXY_OID = UUID.nameUUIDFromBytes("proxy".getBytes());
    private static final UUID STAFF_OID = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());
    private static final UUID LINKED_PLAYER = UUID.nameUUIDFromBytes("linked-player".getBytes());
    private static final String EXEMPT = ExemptionService.ownerOf(PLAYER);
    private static final String LINKED = ExemptionService.ownerOf(LINKED_PLAYER);
    private static final String PLAIN = ExemptionService.ownerOf(UUID.nameUUIDFromBytes("plain".getBytes()));
    private static final String BAN = "ban";
    private static final String WARN = "warn";
    private static final String KICK = "kick-step";

    private static final Actor MOD = new Actor(MOD_OID, "Mod", ActorKind.HUMAN, false, false);
    private static final Actor ADMIN = new Actor(ADMIN_OID, "Admin", ActorKind.HUMAN, false, true);
    private static final Actor PROXY = new Actor(PROXY_OID, "Proxy", ActorKind.SERVICE, true);

    private final PunishmentTestBed bed = new PunishmentTestBed();

    private void givenTemplates() {
        bed.givenTemplate(BAN, "network", BAN, PunishmentTestBed.step("PT1H", 0));
        bed.givenTemplate(WARN, "network", WARN, new TemplateStep(StepKind.WARN, null, 0));
        bed.givenTemplate(KICK, "network", KICK, new TemplateStep(StepKind.KICK, null, 0));
    }

    private void givenPermissionExempt() {
        bed.staffLinks.exemptions.report(new ExemptionDTO.ReportRequest(EXEMPT, List.of("*")));
    }

    private void givenLinkedAdmin() {
        StaffActorEntity staff = new StaffActorEntity(ADMIN_TARGET_OID, ActorKind.HUMAN, "Admin target", 1L);
        staff.setRoles("ADMIN,MODERATOR,VIEWER");
        bed.staffLinks.actors.given(staff, ADMIN_TARGET_OID);
        bed.staffLinks.exemptions.linkStaff(ADMIN_TARGET, ADMIN_TARGET_OID);
    }

    private void givenLinkedModerator() {
        StaffActorEntity staff = new StaffActorEntity(STAFF_OID, ActorKind.HUMAN, "Staff", 1L);
        staff.setRoles("MODERATOR,VIEWER");
        bed.staffLinks.actors.given(staff, STAFF_OID);
        bed.staffLinks.givenLink(STAFF_OID, LINKED_PLAYER);
        bed.staffLinks.exemptions.linkStaff(LINKED, STAFF_OID);
    }

    private PunishmentDTO apply(String owner, String template, Actor actor, UUID onBehalfOf, ExemptionDTO.Override override) {
        return bed.punishmentService.apply(new PunishmentDTO.ApplyRequest(owner, template, null, onBehalfOf, null, null, null,
                null, null, override), actor);
    }

    private void assertRefusedAndNothingWritten(PunishmentDTO result) {
        var error = assertInstanceOf(PunishmentDTO.Error.class, result, "the write must be refused");
        assertEquals(ErrorKind.TARGET_EXEMPT, error.kind());
        assertTrue(bed.punishments.all().isEmpty(), "no punishment is written");
        assertTrue(bed.profiles.all().isEmpty(), "no profile slot is taken");
    }

    @Test
    void aModeratorApplyingABanToAPermissionExemptPlayerIsRefusedAndWritesNothing() {
        givenTemplates();
        givenPermissionExempt();

        assertRefusedAndNothingWritten(apply(EXEMPT, BAN, MOD, null, null));
    }

    @Test
    void aWarningOfAnExemptPlayerIsRefused() {
        givenTemplates();
        givenPermissionExempt();

        assertRefusedAndNothingWritten(apply(EXEMPT, WARN, MOD, null, null));
    }

    @Test
    void aTemplateKickOfAnExemptPlayerIsRefused() {
        givenTemplates();
        givenPermissionExempt();

        assertRefusedAndNothingWritten(apply(EXEMPT, KICK, MOD, null, null));
    }

    @Test
    void aProxyDelegatedApplyOfALinkedModeratorIsRefusedForAModeratorRank() {
        givenTemplates();
        givenLinkedModerator();

        assertRefusedAndNothingWritten(apply(LINKED, BAN, PROXY, LINKED_PLAYER, null));
    }

    @Test
    void anAdminOverrideAppliesTheBanAndRecordsTheJustificationOnThePunishment() {
        givenTemplates();
        givenLinkedAdmin();

        PunishmentDTO result = apply(ADMIN_TARGET, BAN, ADMIN, null, new ExemptionDTO.Override("reviewed by the council"));

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the admin override lets the write through");
        assertEquals(1, bed.punishments.all().size(), "the punishment is written");
        Object override = bed.punishments.all().getFirst().getMetaData().get(ExemptionGuard.OVERRIDE_METADATA_KEY);
        assertTrue(override instanceof java.util.Map<?, ?> map && "reviewed by the council".equals(map.get("justification")),
                "the justification is stored on the punishment");
    }

    @Test
    void aModeratorOverrideIsForbiddenAndWritesNothing() {
        givenTemplates();
        givenPermissionExempt();

        PunishmentDTO result = apply(EXEMPT, BAN, MOD, null, new ExemptionDTO.Override("trying anyway"));

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(PunishmentDTO.Error.class, result).kind());
        assertTrue(bed.punishments.all().isEmpty(), "nothing is written");
    }

    @Test
    void aProxyDelegatedOverrideIsRefusedAsBadRequest() {
        givenTemplates();
        givenLinkedModerator();

        PunishmentDTO result = apply(LINKED, BAN, PROXY, LINKED_PLAYER, new ExemptionDTO.Override("in game"));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(PunishmentDTO.Error.class, result).kind());
        assertTrue(bed.punishments.all().isEmpty(), "nothing is written");
    }

    @Test
    void aManualKickOfAnExemptPlayerIsRefusedAndWritesNothing() {
        givenPermissionExempt();

        KickDTO result = bed.kickService.kick(new KickDTO.CreateRequest(EXEMPT, KickScope.NETWORK, null, "reason"), "kick-key-1", MOD);

        assertEquals(ErrorKind.TARGET_EXEMPT, assertInstanceOf(KickDTO.Error.class, result).kind());
        assertTrue(bed.punishments.all().isEmpty(), "no kick row is written");
    }

    @Test
    void anAdminManualKickOverrideIsAllowed() {
        givenPermissionExempt();

        KickDTO result = bed.kickService.kick(new KickDTO.CreateRequest(EXEMPT, KickScope.NETWORK, null, "reason",
                new ExemptionDTO.Override("confirmed cheating")), "kick-key-2", ADMIN);

        assertInstanceOf(KickDTO.Accepted.class, result, "the admin override lets the kick through");
    }

    @Test
    void anAdminOverrideOfAWarningRecordsTheJustificationOnTheWarningRow() {
        givenTemplates();
        givenLinkedAdmin();

        PunishmentDTO result = apply(ADMIN_TARGET, WARN, ADMIN, null, new ExemptionDTO.Override("warned after the review"));

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the admin override lets the warning through");
        Object override = bed.punishments.all().getFirst().getMetaData().get(ExemptionGuard.OVERRIDE_METADATA_KEY);
        assertTrue(override instanceof java.util.Map<?, ?> map && "warned after the review".equals(map.get("justification")),
                "the justification is stored on the warning row");
    }

    @Test
    void anAdminOverrideOfATemplateKickRecordsTheJustificationOnTheKickRow() {
        givenTemplates();
        givenLinkedAdmin();

        PunishmentDTO result = apply(ADMIN_TARGET, KICK, ADMIN, null, new ExemptionDTO.Override("kicked after the review"));

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, result, "the admin override lets the kick step through");
        Object override = bed.punishments.all().getFirst().getMetaData().get(ExemptionGuard.OVERRIDE_METADATA_KEY);
        assertTrue(override instanceof java.util.Map<?, ?> map && "kicked after the review".equals(map.get("justification")),
                "the justification is stored on the kick row");
    }

    @Test
    void anOverrideJustificationNamingAPlayerOrAnAddressIsRefusedAndWritesNothing() {
        givenTemplates();
        givenLinkedAdmin();

        PunishmentDTO withUuid = apply(ADMIN_TARGET, BAN, ADMIN, null,
                new ExemptionDTO.Override("see 123e4567-e89b-42d3-a456-426614174000"));
        PunishmentDTO withIp = apply(ADMIN_TARGET, BAN, ADMIN, null, new ExemptionDTO.Override("came from 10.0.0.7"));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(PunishmentDTO.Error.class, withUuid).kind(), "a UUID is refused");
        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(PunishmentDTO.Error.class, withIp).kind(), "an IP address is refused");
        assertTrue(bed.punishments.all().isEmpty(), "nothing is written");
    }

    @Test
    void aNonExemptPlayerIsStillBanned() {
        givenTemplates();

        assertInstanceOf(PunishmentDTO.ApplyResponse.class, apply(PLAIN, BAN, MOD, null, null), "exemption changes nothing here");
    }
}
