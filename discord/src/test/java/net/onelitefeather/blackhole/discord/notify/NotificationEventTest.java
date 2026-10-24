package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationKind;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.NotificationResult;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.Notification;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationEventTest {

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aReportCreatedEnvelopeNamesTheReport() {
        Optional<NotificationEvent> event = NotificationEvent.parse(json(
                "{\"eventId\":\"e1\",\"eventType\":\"report.created\",\"occurredAt\":1,\"payloadVersion\":1,"
                        + "\"payload\":{\"reportIdentifier\":\"r1\",\"reporterHash\":\"h\",\"reportedHash\":\"h2\",\"category\":\"CHEATING\"}}"));

        assertEquals(Optional.of(new NotificationEvent("e1", "report.created", NotificationKind.REPORT, "r1")), event, "parsed");
    }

    @Test
    void anAppealResolvedEnvelopeNamesTheAppeal() {
        Optional<NotificationEvent> event = NotificationEvent.parse(json(
                "{\"eventId\":\"e2\",\"eventType\":\"appeal.resolved\",\"payload\":{\"appealIdentifier\":\"a1\",\"decision\":\"DENIED\"}}"));

        assertEquals(Optional.of(new NotificationEvent("e2", "appeal.resolved", NotificationKind.APPEAL, "a1")), event, "parsed");
    }

    @Test
    void malformedUnknownAndIncompleteMessagesAreNotEvents() {
        assertTrue(NotificationEvent.parse(json("not json")).isEmpty(), "garbage");
        assertTrue(NotificationEvent.parse(json("[]")).isEmpty(), "not an object");
        assertTrue(NotificationEvent.parse(json("{\"eventId\":\"e\",\"eventType\":\"punishment.created\",\"payload\":{}}")).isEmpty(), "other type");
        assertTrue(NotificationEvent.parse(json("{\"eventId\":\"e\",\"eventType\":\"report.created\",\"payload\":{}}")).isEmpty(), "no identifier");
        assertTrue(NotificationEvent.parse(json("{\"eventType\":\"report.created\",\"payload\":{\"reportIdentifier\":\"r\"}}")).isEmpty(), "no event id");
    }

    @Test
    void theDispatcherDropsNonEventsAndRetriesAnUnexpectedFailure() {
        ScriptedBackend backend = new ScriptedBackend();
        FakeDiscord discord = new FakeDiscord();
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                new NotificationHandler(backend, discord, new EmbedRenderer("https://panel.example.org", 1L), 2L));

        assertEquals(Outcome.DROP, dispatcher.onMessage(json("nope")), "not an event");

        backend.report = new NotificationResult.Found(new Notification(NotificationKind.REPORT, "r1", null, null, "ref", "OTHER", null, "OPEN", 1L, 1L));
        discord.failure = null;
        assertEquals(Outcome.DONE, dispatcher.onMessage(json(
                "{\"eventId\":\"e1\",\"eventType\":\"report.created\",\"payload\":{\"reportIdentifier\":\"r1\"}}")), "a real event is handled");
        assertEquals(1, discord.messages.size(), "and posted");
    }

    @Test
    void theRoutingKeysAreExactlyTheFourEventsTheBotHandles() {
        assertEquals(java.util.List.of("report.created", "report.resolved", "appeal.submitted", "appeal.resolved"),
                java.util.List.of(NotificationEvent.ROUTING_KEYS), "subscriptions");
    }
}
