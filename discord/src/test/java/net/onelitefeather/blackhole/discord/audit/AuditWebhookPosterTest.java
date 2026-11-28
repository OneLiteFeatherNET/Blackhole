package net.onelitefeather.blackhole.discord.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.discord.audit.AuditWebhookPoster.Delivery;
import net.onelitefeather.blackhole.discord.audit.WebhookTransport.Answer;
import net.onelitefeather.blackhole.discord.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditWebhookPosterTest {

    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/SECRET-TOKEN-4711";
    private static final String AUDIT_ID = "7d1c2a4e-0000-4000-8000-000000000001";
    private static final String JSON = "{\"embeds\":[]}";
    private static final String RATE_LIMITED = "{\"message\":\"You are being rate limited.\",\"retry_after\":2.5,\"global\":false}";

    private record Poster(AuditWebhookPoster poster, FakeWebhookTransport transport, RecordingSleeper sleeper, Metrics metrics) {
    }

    private static Poster poster(Object... script) {
        FakeWebhookTransport transport = new FakeWebhookTransport(URL, script);
        RecordingSleeper sleeper = new RecordingSleeper();
        Metrics metrics = new Metrics();
        return new Poster(new AuditWebhookPoster(transport, sleeper, metrics), transport, sleeper, metrics);
    }

    @Test
    void aRateLimitIsRetriedAfterTheRetryAfterValueOfTheBody() {
        Poster p = poster(new Answer(429, RATE_LIMITED, null), new Answer(204, "", null));

        Delivery delivery = p.poster().post(AUDIT_ID, JSON);

        assertEquals(Delivery.DELIVERED, delivery, "delivered on the second attempt");
        assertEquals(List.of(Duration.ofMillis(2500)), p.sleeper().sleeps, "the server asked for 2.5 seconds");
        assertEquals(2, p.transport().bodies.size(), "the entry was sent twice");
        assertEquals(1, p.metrics().value(Metrics.Counter.AUDIT_RETRIED), "one retry was counted");
    }

    @Test
    void aRateLimitWithoutABodyFallsBackToTheRetryAfterHeader() {
        Poster p = poster(new Answer(429, "", "3"), new Answer(204, "", null));

        p.poster().post(AUDIT_ID, JSON);

        assertEquals(List.of(Duration.ofSeconds(3)), p.sleeper().sleeps, "the header says three seconds");
    }

    @Test
    void aServerErrorIsRetriedWithGrowingDelays() {
        Poster p = poster(new Answer(500, "", null), new Answer(502, "", null), new Answer(500, "", null), new Answer(204, "", null));

        Delivery delivery = p.poster().post(AUDIT_ID, JSON);

        assertEquals(Delivery.DELIVERED, delivery, "delivered on the fourth attempt");
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), p.sleeper().sleeps,
                "exponential backoff from one second");
    }

    @Test
    void aNetworkErrorIsRetriedLikeAServerError() {
        Poster p = poster(new IOException("connection reset"), new Answer(204, "", null));

        Delivery delivery = p.poster().post(AUDIT_ID, JSON);

        assertEquals(Delivery.DELIVERED, delivery, "delivered after the network error");
        assertEquals(1, p.sleeper().sleeps.size(), "one backoff before the retry");
    }

    @Test
    void aClientErrorIsNotRetriedAndIsCountedAsRejected() {
        Poster p = poster(new Answer(404, "{\"message\":\"Unknown Webhook\"}", null));

        Delivery delivery = p.poster().post(AUDIT_ID, JSON);

        assertEquals(Delivery.REJECTED, delivery, "a deleted webhook is a final rejection");
        assertEquals(1, p.transport().bodies.size(), "sent once");
        assertTrue(p.sleeper().sleeps.isEmpty(), "never waited");
        assertEquals(1, p.metrics().value(Metrics.Counter.AUDIT_REJECTED), "the rejection was counted");
    }

    @Test
    void anUnauthorizedAnswerIsNotRetriedEither() {
        Poster p = poster(new Answer(401, "", null));

        assertEquals(Delivery.REJECTED, p.poster().post(AUDIT_ID, JSON), "401 is final");
        assertEquals(1, p.transport().bodies.size(), "sent once");
    }

    @Test
    void aWebhookThatNeverRecoversIsGivenUpForNowNotDropped() {
        Object[] script = new Object[AuditWebhookPoster.MAX_ATTEMPTS];
        Arrays.fill(script, new Answer(503, "", null));
        Poster p = poster(script);

        Delivery delivery = p.poster().post(AUDIT_ID, JSON);

        assertEquals(Delivery.UNDELIVERED, delivery, "the caller keeps the entry for a later attempt");
        assertEquals(AuditWebhookPoster.MAX_ATTEMPTS, p.transport().bodies.size(), "every attempt was made");
    }

    @Test
    void theBackoffDoublesAndStopsAtFiveMinutes() {
        assertEquals(Duration.ofSeconds(1), AuditWebhookPoster.backoffFor(1), "first retry after one second");
        assertEquals(Duration.ofSeconds(8), AuditWebhookPoster.backoffFor(4), "fourth retry after eight seconds");
        assertEquals(Duration.ofMinutes(5), AuditWebhookPoster.backoffFor(40), "capped at five minutes");
    }

    @Test
    void theWebhookUrlNeverReachesTheLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Level before = root.getLevel();
        root.setLevel(Level.TRACE);
        root.addAppender(captured);
        try {
            poster(new Answer(500, "", null), new IOException("refused by " + URL), new Answer(404, "", null)).poster().post(AUDIT_ID, JSON);
            poster(new Answer(401, "", null)).poster().post(AUDIT_ID, JSON);
        } finally {
            root.detachAppender(captured);
            root.setLevel(before);
        }

        List<String> lines = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertFalse(lines.isEmpty(), "the poster logged the failures");
        for (String line : lines) {
            assertFalse(line.contains("SECRET-TOKEN-4711") || line.contains("discord.com/api/webhooks"), "no webhook URL or token in: " + line);
        }
    }
}
