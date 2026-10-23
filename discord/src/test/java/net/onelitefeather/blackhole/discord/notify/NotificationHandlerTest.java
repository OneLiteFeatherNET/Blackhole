package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.Notification;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationKind;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationResult;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.MessageSpec;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationHandlerTest {

    private static final long CHANNEL = 222222222222222222L;
    private static final long ROLE = 333333333333333333L;
    private static final String REPORT = "11111111-1111-4111-8111-111111111111";
    private static final String APPEAL = "22222222-2222-4222-8222-222222222222";

    private final ScriptedBackend backend = new ScriptedBackend();
    private final FakeDiscord discord = new FakeDiscord();
    private final NotificationHandler handler = new NotificationHandler(
            this.backend.gateway(), this.discord, new EmbedRenderer("https://panel.example.org", ROLE), CHANNEL);

    private static Notification report(String status) {
        return new Notification(NotificationKind.REPORT, REPORT, null, "Griefer_01", "01234567", "GRIEFING", "blew up spawn", status, 1_000_000L, 2_000_000L);
    }

    private static Notification appeal(String status) {
        return new Notification(NotificationKind.APPEAL, APPEAL, "punishment-1", null, "89abcdef", "chat", "please", status, 1_000_000L, 2_000_000L);
    }

    private static NotificationEvent event(String id, String type, NotificationKind kind, String identifier) {
        return new NotificationEvent(id, type, kind, identifier);
    }

    private MessageSpec onlyMessage() {
        assertEquals(1, this.discord.messages.size(), "exactly one team message");
        return this.discord.messages.values().iterator().next();
    }

    @Test
    void aNewReportIsPostedToTheTeamChannelWithARolePingAndAPanelLink() {
        this.backend.report = new NotificationResult.Found(report("OPEN"));

        Outcome outcome = this.handler.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT));

        assertEquals(Outcome.DONE, outcome, "handled");
        MessageSpec message = onlyMessage();
        assertEquals("<@&" + ROLE + ">", message.content(), "the staff role is pinged");
        assertEquals("New report · Neue Meldung", message.title(), "bilingual title");
        assertEquals("https://panel.example.org/reports", message.linkUrl(), "link into the panel");
        assertEquals("Blackhole · ref:report:" + REPORT, message.footer(), "marker for finding it again");
        assertEquals(CHANNEL, this.discord.messages.keySet().iterator().next().channelId(), "team channel");
    }

    @Test
    void theMessageShowsNameCategoryReasonAndTimeButNoIdentifierOfThePlayer() {
        this.backend.report = new NotificationResult.Found(report("OPEN"));

        this.handler.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT));

        String all = onlyMessage().fields().toString();
        assertTrue(all.contains("Griefer_01"), "name: " + all);
        assertTrue(all.contains("Griefing"), "category: " + all);
        assertTrue(all.contains("blew up spawn"), "reason: " + all);
        assertTrue(all.contains("<t:1000:"), "time: " + all);
    }

    @Test
    void withoutANameTheShortPseudonymousReferenceIsShown() {
        this.backend.appeal = new NotificationResult.Found(appeal("ELIGIBLE_PENDING_REVIEW"));

        this.handler.handle(event("e1", "appeal.submitted", NotificationKind.APPEAL, APPEAL));

        assertTrue(onlyMessage().fields().toString().contains("`89abcdef`"), "reference shown: " + onlyMessage().fields());
    }

    @Test
    void anAppealLinksToItsCaseNotToABanId() {
        this.backend.appeal = new NotificationResult.Found(appeal("ELIGIBLE_PENDING_REVIEW"));

        this.handler.handle(event("e1", "appeal.submitted", NotificationKind.APPEAL, APPEAL));

        assertEquals("https://panel.example.org/cases/punishment-1", onlyMessage().linkUrl(), "case link");
        assertEquals("New appeal · Neuer Einspruch", onlyMessage().title(), "bilingual title");
    }

    @Test
    void aRedeliveredEventPostsNoSecondMessageAndAsksNothingAgain() {
        this.backend.report = new NotificationResult.Found(report("OPEN"));
        NotificationEvent created = event("e1", "report.created", NotificationKind.REPORT, REPORT);

        this.handler.handle(created);
        int callsAfterFirst = this.backend.calls;
        Outcome second = this.handler.handle(created);

        assertEquals(Outcome.DONE, second, "acknowledged");
        assertEquals(1, this.discord.messages.size(), "still one message");
        assertEquals(callsAfterFirst, this.backend.calls, "short-circuited by the event id");
    }

    @Test
    void aRestartedBotFindsItsMessageByTheMarkerAndPostsNoDuplicate() {
        this.backend.report = new NotificationResult.Found(report("OPEN"));
        this.handler.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT));
        NotificationHandler restarted = new NotificationHandler(
                this.backend.gateway(), this.discord, new EmbedRenderer("https://panel.example.org", ROLE), CHANNEL);

        restarted.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT));

        assertEquals(1, this.discord.messages.size(), "the marker made the second delivery an edit");
        assertEquals(List.of("find", "post", "find", "edit"), this.discord.calls, "post once, then edit");
    }

    @Test
    void resolvingAReportEditsTheMessageToShowTheOutcomeAndDropsThePing() {
        this.backend.report = new NotificationResult.Found(report("OPEN"));
        this.handler.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT));
        this.backend.report = new NotificationResult.Found(report("ACTIONED"));

        Outcome outcome = this.handler.handle(event("e2", "report.resolved", NotificationKind.REPORT, REPORT));

        assertEquals(Outcome.DONE, outcome, "handled");
        MessageSpec message = onlyMessage();
        assertEquals("Report · Meldung: Actioned · Bearbeitet", message.title(), "outcome in the title");
        assertNull(message.content(), "no ping any more");
        assertEquals(EmbedRenderer.DONE_COLOR, message.color(), "green");
        assertTrue(message.fields().toString().contains("Outcome · Ergebnis"), "outcome field");
        assertEquals(List.of("find", "post", "find", "edit"), this.discord.calls, "one post, one edit");
    }

    @Test
    void aDeniedAppealIsShownInRed() {
        this.backend.appeal = new NotificationResult.Found(appeal("DENIED"));

        this.handler.handle(event("e1", "appeal.resolved", NotificationKind.APPEAL, APPEAL));

        assertEquals(EmbedRenderer.REJECTED_COLOR, onlyMessage().color(), "red");
        assertEquals("Appeal · Einspruch: Denied · Abgelehnt", onlyMessage().title(), "outcome");
    }

    @Test
    void aResolutionThatArrivesBeforeTheCreationStillGivesOneMessage() {
        this.backend.report = new NotificationResult.Found(report("DISMISSED"));

        this.handler.handle(event("e2", "report.resolved", NotificationKind.REPORT, REPORT));
        this.handler.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT));

        assertEquals(1, this.discord.messages.size(), "one message in the resolved state");
        assertEquals(EmbedRenderer.REJECTED_COLOR, onlyMessage().color(), "dismissed");
    }

    @Test
    void anIneligibleAppealGetsNoTeamMessage() {
        this.backend.appeal = new NotificationResult.Found(appeal("INELIGIBLE"));

        Outcome outcome = this.handler.handle(event("e1", "appeal.submitted", NotificationKind.APPEAL, APPEAL));

        assertEquals(Outcome.DONE, outcome, "nothing to do");
        assertTrue(this.discord.messages.isEmpty(), "no message");
    }

    @Test
    void anUnknownItemIsDroppedAndAnUnavailableBackendIsRetried() {
        this.backend.report = new NotificationResult.NotFound();
        assertEquals(Outcome.DROP, this.handler.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT)), "unknown item");

        this.backend.report = new NotificationResult.Unavailable(503);
        assertEquals(Outcome.RETRY, this.handler.handle(event("e2", "report.created", NotificationKind.REPORT, REPORT)), "backend down");
        assertTrue(this.discord.messages.isEmpty(), "nothing posted");
    }

    @Test
    void aTemporaryDiscordFailureIsRetriedAndAMissingAccessIsDropped() {
        this.backend.report = new NotificationResult.Found(report("OPEN"));
        this.discord.failure = new GatewayException("rate limited", false, null);
        assertEquals(Outcome.RETRY, this.handler.handle(event("e1", "report.created", NotificationKind.REPORT, REPORT)), "temporary");

        this.discord.failure = new GatewayException("missing access", true, null);
        assertEquals(Outcome.DROP, this.handler.handle(event("e2", "report.created", NotificationKind.REPORT, REPORT)), "permanent");
    }

    @Test
    void aFailedEventIsNotRememberedSoItsRetrySucceeds() {
        this.backend.report = new NotificationResult.Found(report("OPEN"));
        this.discord.failure = new GatewayException("rate limited", false, null);
        NotificationEvent created = event("e1", "report.created", NotificationKind.REPORT, REPORT);
        this.handler.handle(created);
        this.discord.failure = null;

        Outcome retried = this.handler.handle(created);

        assertEquals(Outcome.DONE, retried, "delivered on the retry");
        assertEquals(1, this.discord.messages.size(), "now posted");
    }
}
