package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guard and area ban delegation: which machine may act for which staff member, and whose role applies. */
class GuardDelegationTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID MINECRAFT = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final UUID PROXY_OID = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
    private static final UUID BOT_OID = UUID.fromString("00000000-0000-4000-8000-0000000000c3");
    private static final UUID MODERATOR = UUID.fromString("00000000-0000-4000-8000-0000000000d1");
    private static final String DISCORD_USER = "1234567890";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);
    private final StaffDelegations.Linked linked = StaffDelegations.linked(this.clock);

    private Actor proxy() {
        return new Actor(PROXY_OID, "proxy", ActorKind.SERVICE, true, false, false, false);
    }

    private Actor bot() {
        return new Actor(BOT_OID, "bot", ActorKind.SERVICE, false, false, false, true);
    }

    private Actor human(boolean admin) {
        return new Actor(MODERATOR, "Moderator", ActorKind.HUMAN, false, admin);
    }

    private StaffDelegation.Outcome.Refused refusedOf(StaffDelegation.Outcome outcome) {
        return assertInstanceOf(StaffDelegation.Outcome.Refused.class, outcome, "the delegation must be refused");
    }

    private StaffDelegation.Outcome.Allowed allowedOf(StaffDelegation.Outcome outcome) {
        return assertInstanceOf(StaffDelegation.Outcome.Allowed.class, outcome, "the delegation must be allowed");
    }

    @Test
    void theProxyActsForAStaffMemberWithALiveLinkAndAFreshModeratorSnapshot() {
        this.linked.givenInGameStaff(STAFF, MINECRAFT);
        this.linked.givenSnapshot(STAFF, "MODERATOR,VIEWER", this.clock.millis());

        StaffDelegation.Outcome.Allowed allowed = allowedOf(this.linked.delegation().attributeGuard(proxy(), MINECRAFT));

        assertEquals(MINECRAFT, allowed.attribution().source(), "the write is recorded under the in-game account, as punishments are");
        assertEquals(PROXY_OID, allowed.attribution().recordedByOid(), "the proxy's token is the one recorded");
        assertEquals(STAFF, allowed.attribution().actorOid(), "the linked staff member is the actor of the write");
        assertFalse(allowed.admin(), "a moderator snapshot is not an admin");
    }

    @Test
    void theProxyNamingAStaffOidInsteadOfAMinecraftAccountIsRefusedAsNotEligible() {
        this.linked.givenInGameStaff(STAFF, MINECRAFT);
        this.linked.givenSnapshot(STAFF, "MODERATOR", this.clock.millis());

        StaffDelegation.Outcome.Refused refused = refusedOf(this.linked.delegation().attributeGuard(proxy(), STAFF));

        assertEquals(StaffDelegation.Reason.NOT_ELIGIBLE, refused.reason(),
                "the proxy names a Minecraft account; an oid with no account link of its own has no live staff link");
    }

    @Test
    void theRoleOfTheDelegatedStaffMemberIsResolvedFromItsSnapshotNotFromTheMachine() {
        this.linked.givenInGameStaff(STAFF, MINECRAFT);
        this.linked.givenSnapshot(STAFF, "ADMIN,MODERATOR,VIEWER", this.clock.millis());

        StaffDelegation.Outcome.Allowed allowed = allowedOf(this.linked.delegation().attributeGuard(proxy(), MINECRAFT));

        assertTrue(allowed.admin(), "an admin snapshot makes the delegated write an admin write");
    }

    @Test
    void theProxyCannotActForAMinecraftAccountWithoutALiveStaffLink() {
        this.linked.givenSnapshot(STAFF, "MODERATOR", this.clock.millis());

        StaffDelegation.Outcome.Refused refused = refusedOf(this.linked.delegation().attributeGuard(proxy(), MINECRAFT));

        assertEquals(StaffDelegation.Reason.NOT_ELIGIBLE, refused.reason(), "an unlinked account has no staff member to act for");
        assertEquals(ErrorKind.FORBIDDEN, refused.errorKind(), "an unlinked staff member is a refusal of authority");
    }

    @Test
    void theProxyCannotActForAStaffMemberWhoseSnapshotIsStale() {
        this.linked.givenInGameStaff(STAFF, MINECRAFT);
        this.linked.givenSnapshot(STAFF, "MODERATOR", this.clock.millis() - 25L * 3600_000L);

        StaffDelegation.Outcome.Refused refused = refusedOf(this.linked.delegation().attributeGuard(proxy(), MINECRAFT));

        assertEquals(StaffDelegation.Reason.ROLE_STALE, refused.reason(), "a snapshot older than the window is stale");
    }

    @Test
    void theProxyCannotActForAStaffMemberWithoutTheModeratorOrAdminRole() {
        this.linked.givenInGameStaff(STAFF, MINECRAFT);
        this.linked.givenSnapshot(STAFF, "VIEWER", this.clock.millis());

        StaffDelegation.Outcome.Refused refused = refusedOf(this.linked.delegation().attributeGuard(proxy(), MINECRAFT));

        assertEquals(StaffDelegation.Reason.NOT_ELIGIBLE, refused.reason(), "a viewer may not be acted for");
    }

    @Test
    void theProxyMustNameTheStaffMemberItActsFor() {
        Actor machine = proxy();

        StaffDelegation.Outcome.Refused refused = refusedOf(this.linked.delegation().attributeGuard(machine, null));

        assertEquals(StaffDelegation.Reason.REQUIRED, refused.reason(), "a machine without onBehalfOf is refused");
        assertEquals(ErrorKind.FORBIDDEN, refused.errorKind(), "a missing onBehalfOf is a refusal of authority, as for the bot");
    }

    @Test
    void aHumanModeratorMayNotNameAnotherStaffMember() {
        StaffDelegation.Outcome.Refused refused = refusedOf(this.linked.delegation().attributeGuard(human(false), STAFF));

        assertEquals(ErrorKind.VALIDATION, refused.errorKind(), "onBehalfOf from a person is a malformed request");
    }

    @Test
    void aHumanActsForItselfWithItsOwnAdminFlag() {
        StaffDelegation.Outcome.Allowed allowed = allowedOf(this.linked.delegation().attributeGuard(human(true), null));

        assertEquals(MODERATOR, allowed.attribution().source(), "a person's write is its own");
        assertTrue(allowed.admin(), "the caller's own admin flag applies to its own write");
    }

    @Test
    void theBotActsForADiscordLinkedStaffMemberAsBefore() {
        this.linked.givenLinkedStaff(STAFF, DISCORD_USER, "ADMIN,MODERATOR,VIEWER", this.clock.millis());

        StaffDelegation.Outcome.Allowed allowed = allowedOf(this.linked.delegation().attributeGuard(bot(), STAFF));

        assertEquals(STAFF, allowed.attribution().source(), "the bot's delegation is the staff member it names");
        assertTrue(allowed.admin(), "the bot's delegated write carries the staff member's admin role");
    }
}
