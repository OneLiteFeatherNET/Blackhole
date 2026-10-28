package net.onelitefeather.blackhole.discord.audit;

import net.onelitefeather.blackhole.discord.audit.WebhookTransport.Answer;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static StaffActionHandler handler(FakeWebhookTransport transport) {
        AuditWebhookPoster poster = new AuditWebhookPoster(transport, new RecordingSleeper(), new Metrics());
        return new StaffActionHandler(poster, new AuditEmbedRenderer("https://panel.example.org"));
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
