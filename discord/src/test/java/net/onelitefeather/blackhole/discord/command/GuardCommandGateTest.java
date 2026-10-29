package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.GuardOutcome;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffLink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gate of {@code /guard}: who may set the level, and that a refused caller never reaches the backend's write. */
class GuardCommandGateTest {

    private static final String USER = "123456789012345678";
    private static final String OID = "7d1f3c2a-0000-4000-8000-00000000a001";

    private final FakeBackend backend = new FakeBackend();
    private final GuardCommands commands = new GuardCommands(this.backend);

    @Test
    void aUserWithoutTheStaffRoleGetsAnEphemeralRefusalAndNoBackendCallIsMade() {
        this.backend.staffLinkResult = new StaffLink.Linked(OID, true);
        this.backend.guardResult = new GuardOutcome.Applied("STRICT", "STRICT");

        String reply = this.commands.set(Lang.EN, USER, false, "STRICT");

        assertEquals("Only the moderation team can set the join guard.", reply, "the refusal text");
        assertTrue(this.backend.staffLookups.isEmpty(), "no staff lookup without the role");
        assertTrue(this.backend.guardCalls.isEmpty(), "no level write without the role");
    }

    @Test
    void aStaffRoleHolderWhoseDiscordAccountIsLinkedSetsTheChosenLevelForThatStaffMember() {
        this.backend.staffLinkResult = new StaffLink.Linked(OID, true);
        this.backend.guardResult = new GuardOutcome.Applied("STRICT", "STRICT");

        this.commands.set(Lang.EN, USER, true, "STRICT");

        assertEquals(List.of(USER), this.backend.staffLookups, "the invoking Discord user is resolved to a staff oid");
        assertEquals(List.of("STRICT/" + OID), this.backend.guardCalls, "one level write, for the resolved staff member");
    }

    @Test
    void aStaffRoleHolderWithoutALinkGetsTheLinkHintAndNoLevelIsSet() {
        this.backend.staffLinkResult = new StaffLink.NotLinked();

        String reply = this.commands.set(Lang.EN, USER, true, "OFF");

        assertTrue(reply.startsWith("Your Discord account is not linked to a moderator account."), "the link hint: " + reply);
        assertTrue(this.backend.guardCalls.isEmpty(), "no write without a staff member to act for");
    }

    @Test
    void anUnknownLevelShowsUsageAndNoBackendCallIsMade() {
        String reply = this.commands.set(Lang.EN, USER, true, "other");

        assertTrue(reply.startsWith("The level must be OFF, STRICT or LOCKDOWN"), "usage: " + reply);
        assertTrue(this.backend.staffLookups.isEmpty(), "no lookup for a level that cannot be set");
        assertTrue(this.backend.guardCalls.isEmpty(), "no write for a level that cannot be set");
    }

    @Test
    void aStaffLookupThatCannotReachTheBackendRepliesUnavailableAndSetsNothing() {
        this.backend.staffLinkResult = new StaffLink.Unavailable(503);

        String reply = this.commands.set(Lang.EN, USER, true, "LOCKDOWN");

        assertEquals("The ban system is not reachable right now. Please try again later.", reply, "unavailable text");
        assertTrue(this.backend.guardCalls.isEmpty(), "no write while the backend is down");
    }
}
