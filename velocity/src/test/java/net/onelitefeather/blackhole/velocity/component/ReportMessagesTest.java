package net.onelitefeather.blackhole.velocity.component;

import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.velocity.report.ReportEvidence;
import net.onelitefeather.blackhole.velocity.report.ReportNotice;
import net.onelitefeather.blackhole.velocity.report.ReportService.Outcome;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportMessagesTest {

    private static Outcome.Submitted submitted(ReportEvidence.Result.Status status, int lines) {
        return new Outcome.Submitted(new ReportDTO().identifier(UUID.randomUUID()),
                new ReportEvidence.Result(status, status == ReportEvidence.Result.Status.ATTACHED ? UUID.randomUUID() : null, lines));
    }

    @Test
    void theConfirmationNamesTheReportedPlayerAndTheReasonInEnglish() {
        String text = Rendered.english(ReportMessages.outcome(submitted(ReportEvidence.Result.Status.NONE, 0), "Steve", ReportCategory.CHEATING)).text();

        assertTrue(text.startsWith("Blackhole"), "the Blackhole prefix must lead the confirmation: " + text);
        assertTrue(text.contains("Steve") && text.contains("cheating"), text);
        assertFalse(text.contains("evidence"), "no evidence line without evidence: " + text);
    }

    @Test
    void theConfirmationIsGermanForGermanPlayers() {
        String text = Rendered.german(ReportMessages.outcome(submitted(ReportEvidence.Result.Status.ATTACHED, 7), "Steve", ReportCategory.CHAT_ABUSE)).text();

        assertTrue(text.contains("Meldung gegen Steve") && text.contains("Chat-Missbrauch"), text);
        assertTrue(text.contains("7 Zeilen") && text.contains("angehängt"), "the evidence line keeps its umlaut: " + text);
    }

    @Test
    void theRateLimitAsksThePlayerToWait() {
        String text = Rendered.english(ReportMessages.outcome(new Outcome.RateLimited(), "Steve", ReportCategory.OTHER)).text();

        assertTrue(text.contains("wait"), text);
    }

    @Test
    void theUnknownReasonListsTheValidOnesAndTheUsage() {
        String text = Rendered.english(ReportMessages.unknownCategory("fly")).text();

        assertTrue(text.contains("fly") && text.contains("chat, cheating, griefing, other") && text.contains("/report <player>"), text);
    }

    @Test
    void thePlayersMessagesCarryNoClicksOrHovers() {
        Rendered rendered = Rendered.english(ReportMessages.outcome(submitted(ReportEvidence.Result.Status.ATTACHED, 3), "Steve", ReportCategory.CHEATING));

        assertFalse(rendered.hasEvents(), "a reporter is not staff and must not get staff command suggestions");
    }

    private static final ReportNotice NOTICE = new ReportNotice("11111111-1111-4111-8111-111111111111", "Alex", "Steve", "CHAT_ABUSE", "so rude");

    @Test
    void theStaffNoticeNamesBothPlayersTheReasonAndTheExcerpt() {
        String text = Rendered.english(ReportMessages.notice(NOTICE, null)).text();

        assertTrue(text.contains("Alex") && text.contains("Steve") && text.contains("chat abuse") && text.contains("so rude"), text);
    }

    @Test
    void theOpenButtonOnlySuggestsTheInfoCommand() {
        Rendered rendered = Rendered.english(ReportMessages.notice(NOTICE, null));

        assertEquals(java.util.List.of("/blackhole Steve info"), rendered.clickValues(), "without a panel address there is only the open button");
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.SUGGEST_COMMAND, rendered.clicks().getFirst().action(), "a click must never run a command");
    }

    @Test
    void thePanelButtonOpensTheReportPageWhenAPanelIsConfigured() {
        Rendered rendered = Rendered.english(ReportMessages.notice(NOTICE, "https://panel.example.org"));

        assertEquals(java.util.List.of("/blackhole Steve info", "https://panel.example.org/reports/11111111-1111-4111-8111-111111111111"),
                rendered.clickValues());
    }

    @Test
    void noPanelButtonWithoutAReportId() {
        ReportNotice withoutId = new ReportNotice(null, "Alex", "Steve", "OTHER", "");

        assertEquals(1, Rendered.english(ReportMessages.notice(withoutId, "https://panel.example.org")).clicks().size());
    }

    @Test
    void theStaffNoticeIsGerman() {
        String text = Rendered.german(ReportMessages.notice(NOTICE, "https://panel.example.org")).text();

        assertTrue(text.contains("Neue Meldung") && text.contains("Chat-Missbrauch") && text.contains("[Öffnen]"), text);
    }
}
