package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.punish.PunishService.RevokeOutcome;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The feedback of /unban and /unmute: one test per way a revoke can end, for players and the console. */
class StaffMessagesRevokeTest {

    private static final List<SectionInfo> BAN_SECTIONS = List.of(TestData.NETWORK, TestData.BEDWARS);
    private static final List<SectionInfo> MUTE_SECTIONS = List.of(TestData.CHAT);

    private static Component unban(RevokeOutcome outcome, String section, Mode mode) {
        return StaffMessages.revoked(outcome, "TheMeinerDev", "unban", section, BAN_SECTIONS, mode);
    }

    private static Component unmute(RevokeOutcome outcome, String section, Mode mode) {
        return StaffMessages.revoked(outcome, "TheMeinerDev", "unmute", section, MUTE_SECTIONS, mode);
    }

    private static RevokeOutcome revoked() {
        return new RevokeOutcome.Revoked(new PunishmentRevokeResponse());
    }

    // ---- Revoked -------------------------------------------------------------------------------

    @Test
    void liftingABanIsConfirmedWithTheSectionName() {
        assertEquals("Blackhole » ✔ Lifted the Network ban of TheMeinerDev.",
                Rendered.english(unban(revoked(), "network", Mode.RICH)).text(), "RICH text");
        assertEquals("Blackhole » ✔ Lifted the Network ban of TheMeinerDev.",
                Rendered.english(unban(revoked(), "network", Mode.PLAIN)).text(), "PLAIN text");
    }

    @Test
    void liftingAMuteIsConfirmed() {
        assertEquals("Blackhole » ✔ Lifted the Chat mute of TheMeinerDev.",
                Rendered.english(unmute(revoked(), "chat", Mode.RICH)).text(), "RICH text");
        assertEquals("Blackhole » ✔ Lifted the Chat mute of TheMeinerDev.",
                Rendered.english(unmute(revoked(), "chat", Mode.PLAIN)).text(), "PLAIN text");
    }

    @Test
    void theGermanConfirmationUsesTheSameParts() {
        assertEquals("Blackhole » ✔ Bann von TheMeinerDev in Network aufgehoben.",
                Rendered.german(unban(revoked(), "network", Mode.RICH)).text(), "unban");
        assertEquals("Blackhole » ✔ Stummschaltung von TheMeinerDev in Chat aufgehoben.",
                Rendered.german(unmute(revoked(), "chat", Mode.RICH)).text(), "unmute");
    }

    // ---- NothingActive -------------------------------------------------------------------------

    @Test
    void nothingActiveNamesTheInfoCommandAsNextStep() {
        RevokeOutcome outcome = new RevokeOutcome.NothingActive("network");

        String expected = "Blackhole » ⚠ TheMeinerDev has no active ban in Network.\n"
                + "  ▸ Check what is active: /blackhole TheMeinerDev info";
        assertEquals(expected, Rendered.english(unban(outcome, "network", Mode.RICH)).text(), "RICH text");
        assertEquals(expected, Rendered.english(unban(outcome, "network", Mode.PLAIN)).text(), "PLAIN text");
    }

    @Test
    void nothingActiveForAMuteSaysMute() {
        String text = Rendered.german(unmute(new RevokeOutcome.NothingActive("chat"), "chat", Mode.RICH)).text();

        assertEquals("Blackhole » ⚠ TheMeinerDev hat in Chat keine aktive Stummschaltung.\n"
                + "  ▸ Prüfe, was aktiv ist: /blackhole TheMeinerDev info", text);
    }

    // ---- PermanentNotAllowed -------------------------------------------------------------------

    @Test
    void aPermanentPunishmentNamesThePermissionItNeeds() {
        RevokeOutcome outcome = new RevokeOutcome.PermanentNotAllowed("network");

        String expected = "Blackhole » ✖ The punishment of TheMeinerDev in Network is permanent.\n"
                + "  ▸ Lifting it needs " + Permissions.PARDON_PERMANENT + ". Ask a team member who has it.";
        assertEquals(expected, Rendered.english(unban(outcome, "network", Mode.RICH)).text(), "RICH text");
        assertEquals(expected, Rendered.english(unban(outcome, "network", Mode.PLAIN)).text(), "PLAIN text");
    }

    // ---- UnknownSection ------------------------------------------------------------------------

    @Test
    void anUnknownSectionListsTheSectionsTheCommandAccepts() {
        RevokeOutcome outcome = new RevokeOutcome.UnknownSection("netwrk");

        String expected = "Blackhole » ✖ There is no section named netwrk.\n"
                + "  ▸ Sections for this command: network, bedwars";
        assertEquals(expected, Rendered.english(unban(outcome, "netwrk", Mode.RICH)).text(), "RICH text");
        assertEquals(expected, Rendered.english(unban(outcome, "netwrk", Mode.PLAIN)).text(), "PLAIN text");
    }

    @Test
    void anUnknownSectionOffersEachSectionAsASuggestionForPlayers() {
        Rendered rendered = Rendered.english(unban(new RevokeOutcome.UnknownSection("netwrk"), "netwrk", Mode.RICH));

        assertTrue(rendered.clickValues().containsAll(List.of("/unban TheMeinerDev network", "/unban TheMeinerDev bedwars")),
                "clicks: " + rendered.clickValues());
    }

    @Test
    void anUnknownSectionWithoutKnownSectionsSaysToTryAgain() {
        Component message = StaffMessages.revoked(new RevokeOutcome.UnknownSection("x"), "TheMeinerDev", "unban", "x", List.of(), Mode.RICH);

        String text = Rendered.english(message).text();
        assertTrue(text.endsWith("No sections are known yet. Try again in a moment."), text);
    }

    // ---- WrongSection --------------------------------------------------------------------------

    @Test
    void aWrongSectionNamesTheCommandAndTheSectionsThatFit() {
        RevokeOutcome outcome = new RevokeOutcome.WrongSection("chat", EnforcementKind.MUTE);

        String expected = "Blackhole » ✖ The section chat cannot be used with /unban.\n"
                + "  ▸ Sections for this command: network, bedwars";
        assertEquals(expected, Rendered.english(unban(outcome, "chat", Mode.RICH)).text(), "RICH text");
        assertEquals(expected, Rendered.english(unban(outcome, "chat", Mode.PLAIN)).text(), "PLAIN text");
    }

    @Test
    void aWrongSectionOfUnmuteListsTheMuteSections() {
        RevokeOutcome outcome = new RevokeOutcome.WrongSection("network", EnforcementKind.BAN);

        assertEquals("Blackhole » ✖ Der Bereich network kann nicht mit /unmute verwendet werden.\n"
                + "  ▸ Bereiche für diesen Befehl: chat", Rendered.german(unmute(outcome, "network", Mode.RICH)).text());
    }

    // ---- Failed --------------------------------------------------------------------------------

    @Test
    void aFailureSaysToTryAgainAndShowsNoStatusCode() {
        RevokeOutcome outcome = new RevokeOutcome.Failed(503);

        String expected = "Blackhole » ✖ The ban could not be lifted. Try again; if it keeps failing, check the backend.";
        assertEquals(expected, Rendered.english(unban(outcome, "network", Mode.RICH)).text(), "RICH text");
        assertEquals(expected, Rendered.english(unban(outcome, "network", Mode.PLAIN)).text(), "PLAIN text");
        assertFalse(expected.contains("503"), "status codes stay in the log");
    }

    @Test
    void aFailureOfUnmuteSaysMute() {
        assertEquals("Blackhole » ✖ Die Stummschaltung konnte nicht aufgehoben werden. Versuche es erneut; falls es weiter scheitert, prüfe das Backend.",
                Rendered.german(unmute(new RevokeOutcome.Failed(0), "chat", Mode.RICH)).text());
    }

    // ---- click and hover -----------------------------------------------------------------------

    @Test
    void theNameSuggestsTheEloCommandInEveryRevokeMessageThatNamesIt() {
        List<RevokeOutcome> withName = List.of(revoked(), new RevokeOutcome.NothingActive("network"), new RevokeOutcome.PermanentNotAllowed("network"));

        for (RevokeOutcome outcome : withName) {
            Rendered rendered = Rendered.english(unban(outcome, "network", Mode.RICH));
            assertTrue(rendered.clickValues().contains("/elo TheMeinerDev"), outcome + " clicks: " + rendered.clickValues());
        }
    }

    @Test
    void theInfoHintSuggestsTheInfoCommandAndNoClickEverRunsACommand() {
        Rendered rendered = Rendered.english(unban(new RevokeOutcome.NothingActive("network"), "network", Mode.RICH));

        assertTrue(rendered.clickValues().contains("/blackhole TheMeinerDev info"), "clicks: " + rendered.clickValues());
        for (ClickEvent click : rendered.clicks()) {
            assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action(), "no click may run a command");
        }
    }

    @Test
    void theConsoleGetsNoEventsInAnyRevokeMessage() {
        List<RevokeOutcome> all = List.of(revoked(), new RevokeOutcome.NothingActive("network"), new RevokeOutcome.PermanentNotAllowed("network"),
                new RevokeOutcome.UnknownSection("x"), new RevokeOutcome.WrongSection("chat", EnforcementKind.MUTE), new RevokeOutcome.Failed(500));

        for (RevokeOutcome outcome : all) {
            assertFalse(Rendered.english(unban(outcome, "network", Mode.PLAIN)).hasEvents(), outcome + " must carry no hover or click in PLAIN");
        }
    }
}
