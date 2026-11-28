package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.GuardOutcome;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffLink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the answer of the level write is shown to the staff member: the effective level, and the refusals in words. */
class GuardCommandsTest {

    private static final String USER = "123456789012345678";
    private static final String OID = "7d1f3c2a-0000-4000-8000-00000000a001";

    private final FakeBackend backend = new FakeBackend();
    private final GuardCommands commands = new GuardCommands(this.backend);

    private String setAndAnswer(GuardOutcome outcome) {
        this.backend.staffLinkResult = new StaffLink.Linked(OID, true);
        this.backend.guardResult = outcome;
        return this.commands.set(Lang.EN, USER, true, "STRICT");
    }

    @Test
    void theConfirmationShowsTheEffectiveLevelBecauseItMayDifferFromTheManualOne() {
        assertEquals("Join guard set to **STRICT**. Effective level now: **LOCKDOWN**.",
                setAndAnswer(new GuardOutcome.Applied("STRICT", "LOCKDOWN")), "english");
    }

    @Test
    void theConfirmationIsGivenInGermanForAGermanSpeaker() {
        this.backend.staffLinkResult = new StaffLink.Linked(OID, true);
        this.backend.guardResult = new GuardOutcome.Applied("OFF", "OFF");

        assertEquals("Join-Guard auf **OFF** gesetzt. Jetzt wirksam: **OFF**.", this.commands.set(Lang.DE, USER, true, "OFF"), "german");
    }

    @Test
    void aStaleStaffRoleAsksToSignInToTheTeamPanelOnce() {
        String reply = setAndAnswer(new GuardOutcome.Refused(403, "STAFF_ROLE_STALE: sign in to the team panel once, then try again"));

        assertEquals("Your moderator role is not recent enough for Discord. Sign in to the team panel once, then try again.", reply, "stale role");
    }

    @Test
    void aStaffMemberWhoIsNoLongerModeratorOrAdminIsToldSo() {
        String reply = setAndAnswer(new GuardOutcome.Refused(403, "STAFF_NOT_ELIGIBLE: the staff member is not a moderator or an admin"));

        assertEquals("Your staff account does not hold the moderator or admin role, so the join guard stays as it is.", reply, "not eligible");
    }

    @Test
    void aConflictAsksToTryAgainAndDoesNotEchoTheBackendText() {
        String reply = setAndAnswer(new GuardOutcome.Refused(409, "the join guard level could not be set, try again"));

        assertEquals("The level could not be set. Try again.", reply, "conflict");
    }

    @Test
    void anOutageIsReportedAsUnavailable() {
        assertEquals("The ban system is not reachable right now. Please try again later.", setAndAnswer(new GuardOutcome.Unavailable(503)),
                "outage");
    }
}
