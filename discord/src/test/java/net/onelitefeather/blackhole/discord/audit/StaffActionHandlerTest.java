package net.onelitefeather.blackhole.discord.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.discord.audit.WebhookTransport.Answer;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffActionHandlerTest {

    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/token-abc";

    private static final String EVENT = """
            {"eventId":"7d1c2a4e-0000-4000-8000-000000000001","eventType":"staff.action",
             "occurredAt":1700000000000,"payloadVersion":1,
             "payload":{"auditId":"7d1c2a4e-0000-4000-8000-000000000001","occurredAt":1700000000000,
               "surface":"PANEL","action":"punishment.revoke","actorOid":"9a8b7c6d-1111-4222-8333-444455556666",
               "actorName":"Ada","targetKind":"CASE","targetRef":"AB12CD34","panelPath":"/cases/AB12CD34"}}
            """;

    private static StaffActionHandler handler(FakeWebhookTransport transport, Metrics metrics) {
        AuditWebhookPoster poster = new AuditWebhookPoster(transport, new RecordingSleeper(), metrics);
        return new StaffActionHandler(poster, new AuditEmbedRenderer("https://panel.example.org"), metrics);
    }

    private static StaffActionHandler handler(FakeWebhookTransport transport) {
        return handler(transport, new Metrics());
    }

    @Test
    void anEntryDroppedAfterItsLastDeliveryIsCountedAndLoggedWithItsIdOnly() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Level before = root.getLevel();
        root.setLevel(Level.TRACE);
        root.addAppender(captured);
        Metrics metrics = new Metrics();
        try {
            handler(new FakeWebhookTransport(URL), metrics).onExhausted(EVENT.getBytes(StandardCharsets.UTF_8));
        } finally {
            root.detachAppender(captured);
            root.setLevel(before);
        }

        assertEquals(1, metrics.value(Metrics.Counter.AUDIT_DROPPED), "the drop was counted");
        List<ILoggingEvent> warnings = captured.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), "one warning");
        String line = warnings.get(0).getFormattedMessage();
        assertTrue(line.contains("7d1c2a4e-0000-4000-8000-000000000001"), "the audit id is named: " + line);
        assertFalse(line.contains("token-abc") || line.contains("discord.com"), "no webhook in: " + line);
    }

    @Test
    void aDeliveredEntryIsAcknowledgedAndPostedWithItsTitle() {
        FakeWebhookTransport transport = new FakeWebhookTransport(URL);

        Outcome outcome = handler(transport).onMessage(EVENT.getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.DONE, outcome, "done");
        assertTrue(transport.bodies.get(0).contains("Punishment revoked"), "the message is the rendered embed");
    }

    @Test
    void aRejectedEntryIsAcknowledgedAndDropped() {
        FakeWebhookTransport transport = new FakeWebhookTransport(URL, new Answer(404, "", null));

        Outcome outcome = handler(transport).onMessage(EVENT.getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.DROP, outcome, "a deleted webhook must not pile up the stream");
    }

    @Test
    void anEntryThatCouldNotBePostedIsRetriedLater() {
        Object[] script = new Object[AuditWebhookPoster.MAX_ATTEMPTS];
        Arrays.fill(script, new Answer(500, "", null));
        FakeWebhookTransport transport = new FakeWebhookTransport(URL, script);

        Outcome outcome = handler(transport).onMessage(EVENT.getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.RETRY, outcome, "the event goes back to the stream");
    }

    @Test
    void aMessageThatIsNotAStaffActionIsDroppedWithoutAPost() {
        FakeWebhookTransport transport = new FakeWebhookTransport(URL);
        String report = "{\"eventId\":\"e1\",\"eventType\":\"report.created\",\"payload\":{\"reportIdentifier\":\"r1\"}}";

        Outcome outcome = handler(transport).onMessage(report.getBytes(StandardCharsets.UTF_8));

        assertEquals(Outcome.DROP, outcome, "not for this consumer");
        assertTrue(transport.bodies.isEmpty(), "nothing was posted");
    }
}
