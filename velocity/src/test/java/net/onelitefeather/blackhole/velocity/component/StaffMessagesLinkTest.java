package net.onelitefeather.blackhole.velocity.component;

import net.onelitefeather.blackhole.velocity.punish.StaffLinkService.LinkOutcome;
import net.onelitefeather.blackhole.velocity.punish.StaffLinkService.WhoamiOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The feedback of /blackhole link and /blackhole whoami: one test per outcome. */
class StaffMessagesLinkTest {

    @Test
    void successNamesTheStaffMember() {
        assertEquals("Blackhole » ✔ Linked this account to Alex Admin. Your in-game actions are now attributed to you.",
                Rendered.english(StaffMessages.linked(new LinkOutcome.Linked("Alex Admin"))).text(), "named success");
    }

    @Test
    void successWithoutANameIsStillConfirmed() {
        assertEquals("Blackhole » ✔ Linked this account to your staff account. Your in-game actions are now attributed to you.",
                Rendered.english(StaffMessages.linked(new LinkOutcome.Linked(null))).text(), "unnamed success");
    }

    @Test
    void invalidAndExpiredCodesGetOneText() {
        assertEquals("Blackhole » ✖ That code is invalid or has expired. Issue a new one in the admin panel.",
                Rendered.english(StaffMessages.linked(new LinkOutcome.InvalidCode())).text(), "english text");
        assertEquals("Blackhole » ✖ Der Code ist ungültig oder abgelaufen. Erzeuge im Admin-Panel einen neuen.",
                Rendered.german(StaffMessages.linked(new LinkOutcome.InvalidCode())).text(), "german text");
    }

    @Test
    void aBackendFailureIsNotMistakenForAnInvalidCode() {
        assertEquals("Blackhole » ✖ The account could not be linked. Try again; if it keeps failing, check the backend.",
                Rendered.english(StaffMessages.linked(new LinkOutcome.Failed(500))).text(), "failure text");
    }

    @Test
    void whoamiNamesTheLinkedStaffMember() {
        assertEquals("Blackhole » ✔ This account is linked to Alex Admin.",
                Rendered.english(StaffMessages.whoami(new WhoamiOutcome.Linked("Alex Admin"))).text(), "english");
        assertEquals("Blackhole » ✔ Dieser Account ist mit Alex Admin verknüpft.",
                Rendered.german(StaffMessages.whoami(new WhoamiOutcome.Linked("Alex Admin"))).text(), "german");
    }

    @Test
    void whoamiOfAnUnlinkedAccountSaysHowToLink() {
        assertEquals("Blackhole » ⚠ This account is not linked. Issue a code in the admin panel and run /blackhole link with it.",
                Rendered.english(StaffMessages.whoami(new WhoamiOutcome.NotLinked())).text(), "unlinked text");
    }

    @Test
    void whoamiReportsALookupFailure() {
        assertEquals("Blackhole » ✖ The link status could not be loaded.",
                Rendered.english(StaffMessages.whoami(new WhoamiOutcome.Failed(0))).text(), "failure text");
    }
}
