package net.onelitefeather.blackhole.discord.notify;

import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import net.onelitefeather.blackhole.discord.sync.CaseEventHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class NatsConsumerTest {

    /** Records what the consumer did with a message. */
    private static final class FakeMessage implements NatsConsumer.Settlement {
        final List<String> calls = new ArrayList<>();
        long deliveries = 1;

        @Override
        public void ack() {
            this.calls.add("ack");
        }

        @Override
        public void nakWithDelay(Duration delay) {
            this.calls.add("nak " + delay.toSeconds() + "s");
        }

        @Override
        public long deliveries() {
            return this.deliveries;
        }
    }

    @Test
    void theAuditConsumerHasOneMessageInFlightSoTheChannelKeepsTheOrder() {
        ConsumerConfiguration configuration = NatsConsumer.consumerConfiguration("discord-audit", new String[]{"staff.action"}, 1,
                Duration.ofMinutes(10), 50);

        assertEquals(1, configuration.getMaxAckPending(), "one unacknowledged message at a time");
        assertEquals(50, configuration.getMaxDeliver(), "the server stops redelivering after the limit");
        assertEquals(Duration.ofMinutes(10), configuration.getAckWait(), "a slow webhook is not redelivered in the middle");
        assertEquals(List.of("blackhole.events.staff.action"), configuration.getFilterSubjects(), "only staff actions");
    }

    @Test
    void aRetryOfTheLastAllowedDeliveryIsAckedAndHandedToTheDropHook() {
        FakeMessage message = new FakeMessage();
        message.deliveries = 50;
        List<String> dropped = new ArrayList<>();

        NatsConsumer.settle(Outcome.RETRY, message, Duration.ofSeconds(10), 50, "body".getBytes(), body -> dropped.add(new String(body)));

        assertEquals(List.of("ack"), message.calls, "the last delivery is not put back");
        assertEquals(List.of("body"), dropped, "the drop hook got the body once");
    }

    @Test
    void aRetryBeforeTheLastAllowedDeliveryIsNakedAndNotDropped() {
        FakeMessage message = new FakeMessage();
        message.deliveries = 49;
        List<String> dropped = new ArrayList<>();

        NatsConsumer.settle(Outcome.RETRY, message, Duration.ofSeconds(10), 50, "body".getBytes(), body -> dropped.add("x"));

        assertEquals(List.of("nak 10s"), message.calls, "one delivery is still left");
        assertTrue(dropped.isEmpty(), "nothing was dropped");
    }

    @Test
    void aMessageWithoutALimitIsNeverDropped() {
        FakeMessage message = new FakeMessage();
        message.deliveries = 10_000;

        NatsConsumer.settle(Outcome.RETRY, message, Duration.ofSeconds(10), -1, new byte[0], body -> fail("no limit"));

        assertEquals(List.of("nak 10s"), message.calls, "the default keeps retrying");
    }

    @Test
    void aHandledMessageIsAcked() {
        FakeMessage message = new FakeMessage();

        NatsConsumer.settle(Outcome.DONE, message, Duration.ofSeconds(10));

        assertEquals(List.of("ack"), message.calls);
    }

    @Test
    void aMessageThatCanNeverSucceedIsAckedToo() {
        FakeMessage message = new FakeMessage();

        NatsConsumer.settle(Outcome.DROP, message, Duration.ofSeconds(10));

        assertEquals(List.of("ack"), message.calls);
    }

    @Test
    void aRetryIsNakedWithTheDelay() {
        FakeMessage message = new FakeMessage();

        NatsConsumer.settle(Outcome.RETRY, message, Duration.ofSeconds(10));

        assertEquals(List.of("nak 10s"), message.calls);
    }

    @Test
    void theNotifyConsumerFiltersTheFourReportAndAppealSubjectsAndHoldsFiveUnacked() {
        ConsumerConfiguration configuration = NatsConsumer.consumerConfiguration(
                NatsConsumer.NOTIFY_DURABLE, NotificationEvent.ROUTING_KEYS);

        assertEquals("blackhole-discord-notify", configuration.getDurable());
        assertEquals(List.of("blackhole.events.report.created", "blackhole.events.report.resolved",
                "blackhole.events.appeal.submitted", "blackhole.events.appeal.resolved"), configuration.getFilterSubjects());
        assertEquals(AckPolicy.Explicit, configuration.getAckPolicy());
        assertEquals(5, configuration.getMaxAckPending());
        assertEquals(Duration.ofSeconds(30), configuration.getAckWait());
        assertEquals(-1, configuration.getMaxDeliver(), "retries are bounded by the stream's max age");
        assertEquals(DeliverPolicy.New, configuration.getDeliverPolicy());
    }

    @Test
    void theCaseConsumerFiltersExactlyTheHandlersEventTypes() {
        ConsumerConfiguration configuration = NatsConsumer.consumerConfiguration(
                NatsConsumer.CASE_DURABLE, CaseEventHandler.ROUTING_KEYS);

        assertEquals("blackhole-discord-case", configuration.getDurable());
        List<String> expected = new ArrayList<>();
        for (String key : CaseEventHandler.ROUTING_KEYS) {
            expected.add("blackhole.events." + key);
        }
        assertEquals(expected, configuration.getFilterSubjects());
    }
}
