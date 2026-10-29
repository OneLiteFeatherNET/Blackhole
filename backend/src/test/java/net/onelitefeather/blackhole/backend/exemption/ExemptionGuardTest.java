package net.onelitefeather.blackhole.backend.exemption;

import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.Attribution;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.support.StaffLinkTestBed;
import net.onelitefeather.blackhole.backend.support.TestClock;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExemptionGuardTest {

    private static final UUID MOD_OID = UUID.nameUUIDFromBytes("mod".getBytes());
    private static final UUID ADMIN_OID = UUID.nameUUIDFromBytes("admin".getBytes());
    private static final UUID LINKED_MOD_OID = UUID.nameUUIDFromBytes("linked-mod".getBytes());
    private static final UUID LINKED_ADMIN_OID = UUID.nameUUIDFromBytes("linked-admin".getBytes());
    private static final String ADMIN_TARGET = ExemptionService.ownerOf(UUID.nameUUIDFromBytes("admin-target".getBytes()));
    private static final UUID PROXY_OID = UUID.nameUUIDFromBytes("proxy".getBytes());
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());
    private static final UUID LINKED_PLAYER = UUID.nameUUIDFromBytes("linked-player".getBytes());
    private static final String EXEMPT = ExemptionService.ownerOf(PLAYER);
    private static final String LINKED = ExemptionService.ownerOf(LINKED_PLAYER);
    private static final String PLAIN = ExemptionService.ownerOf(UUID.nameUUIDFromBytes("plain".getBytes()));

    private static final Actor MOD = new Actor(MOD_OID, "Mod", ActorKind.HUMAN, false, false);
    private static final Actor ADMIN = new Actor(ADMIN_OID, "Admin", ActorKind.HUMAN, false, true);
    private static final Actor PROXY = new Actor(PROXY_OID, "Proxy", ActorKind.SERVICE, true);
    private static final ExemptionDTO.Override JUSTIFIED = new ExemptionDTO.Override("the streamer was reviewed");

    private final StaffLinkTestBed bed = new StaffLinkTestBed(
            new DomainEventPublisher(new RecordingEventTransport(), new TestClock()), new TestClock());

    private ExemptionGuard.Outcome check(String owner, Attribution attribution, Actor actor, ExemptionDTO.Override override) {
        return bed.guard.check(owner, "network", attribution, actor, override);
    }

    private static Attribution webAs(Actor actor) {
        return new Attribution(actor.oid(), actor.oid());
    }

    private static Attribution inGameVia(UUID minecraft) {
        return new Attribution(minecraft, PROXY_OID);
    }

    private void givenPermissionExempt() {
        bed.exemptions.report(new ExemptionDTO.ReportRequest(EXEMPT, List.of("*")));
    }

    private void givenLinkedAdmin() {
        StaffActorEntity actor = new StaffActorEntity(LINKED_ADMIN_OID, ActorKind.HUMAN, "Admin target", 1L);
        actor.setRoles("ADMIN,MODERATOR,VIEWER");
        bed.actors.given(actor, LINKED_ADMIN_OID);
        bed.exemptions.linkStaff(ADMIN_TARGET, LINKED_ADMIN_OID);
    }

    private void givenLinkedModerator() {
        StaffActorEntity actor = new StaffActorEntity(LINKED_MOD_OID, ActorKind.HUMAN, "Linked", 1L);
        actor.setRoles("MODERATOR,VIEWER");
        bed.actors.given(actor, LINKED_MOD_OID);
        bed.links.given(new net.onelitefeather.blackhole.backend.security.StaffLinkEntity(LINKED_MOD_OID, LINKED_PLAYER, 1L, LINKED_MOD_OID),
                LINKED_MOD_OID);
        bed.exemptions.linkStaff(LINKED, LINKED_MOD_OID);
    }

    @Test
    void aModeratorCannotPunishAPermissionExemptPlayer() {
        givenPermissionExempt();

        ExemptionGuard.Outcome outcome = check(EXEMPT, webAs(MOD), MOD, null);

        var refused = assertInstanceOf(ExemptionGuard.Outcome.Refused.class, outcome, "a moderator is refused against rank MODERATOR");
        assertEquals(ErrorKind.TARGET_EXEMPT, refused.kind());
    }

    @Test
    void anAdminMayPunishAPermissionExemptPlayerWithoutOverride() {
        givenPermissionExempt();

        ExemptionGuard.Outcome outcome = check(EXEMPT, webAs(ADMIN), ADMIN, null);

        assertInstanceOf(ExemptionGuard.Outcome.Allowed.class, outcome, "an admin outranks a permission exemption");
    }

    @Test
    void aModeratorCannotPunishALinkedModerator() {
        givenLinkedModerator();

        ExemptionGuard.Outcome outcome = check(LINKED, webAs(MOD), MOD, null);

        assertEquals(ErrorKind.TARGET_EXEMPT, assertInstanceOf(ExemptionGuard.Outcome.Refused.class, outcome).kind(),
                "equal rank is refused");
    }

    @Test
    void aLinkedStaffMemberWithoutARecordedRoleRanksAsModerator() {
        UUID oid = UUID.nameUUIDFromBytes("no-role".getBytes());
        bed.exemptions.linkStaff(LINKED, oid);

        assertEquals(net.onelitefeather.blackhole.backend.exemption.ExemptionRank.MODERATOR, bed.exemptions.staffRank(oid),
                "no recorded role counts as MODERATOR");
    }

    @Test
    void anUnlinkedInGameActorIsRefusedAgainstAnExemptPlayer() {
        givenPermissionExempt();

        ExemptionGuard.Outcome outcome = check(EXEMPT, inGameVia(UUID.nameUUIDFromBytes("ingame".getBytes())), null, null);

        var refused = assertInstanceOf(ExemptionGuard.Outcome.Refused.class, outcome, "no rank can be established");
        assertTrue(refused.message().contains("not linked"), "the refusal says the actor is not linked");
    }

    @Test
    void aNonExemptPlayerIsAlwaysAllowed() {
        ExemptionGuard.Outcome outcome = check(PLAIN, webAs(MOD), MOD, null);

        assertInstanceOf(ExemptionGuard.Outcome.Allowed.class, outcome, "exemption changes nothing for a non-exempt player");
    }

    @Test
    void anAdminOverrideWithJustificationIsAllowedAndRecorded() {
        givenLinkedAdmin();
        ExemptionGuard.Outcome outcome = check(ADMIN_TARGET, webAs(ADMIN), ADMIN, JUSTIFIED);

        var allowed = assertInstanceOf(ExemptionGuard.Outcome.Allowed.class, outcome);
        assertTrue(allowed.overridden(), "the override is recorded");
        assertEquals("ADMIN", allowed.record().get("targetRank"));
        assertEquals("the streamer was reviewed", allowed.record().get("justification"));
    }

    @Test
    void aModeratorCannotOverride() {
        givenPermissionExempt();

        ExemptionGuard.Outcome outcome = check(EXEMPT, webAs(MOD), MOD, JUSTIFIED);

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(ExemptionGuard.Outcome.Refused.class, outcome).kind(),
                "only an admin may override");
    }

    @Test
    void anOverrideWithoutJustificationIsRefused() {
        givenPermissionExempt();

        ExemptionGuard.Outcome outcome = check(EXEMPT, webAs(ADMIN), ADMIN, new ExemptionDTO.Override("   "));

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(ExemptionGuard.Outcome.Refused.class, outcome).kind(),
                "a blank justification is a bad request");
    }

    @Test
    void anOverrideOnAProxyDelegatedWriteIsRefused() {
        givenLinkedModerator();

        ExemptionGuard.Outcome outcome = check(LINKED, inGameVia(LINKED_PLAYER), PROXY, JUSTIFIED);

        assertEquals(ErrorKind.VALIDATION, assertInstanceOf(ExemptionGuard.Outcome.Refused.class, outcome).kind(),
                "an override is never accepted on a write made for an in-game player");
    }
}
