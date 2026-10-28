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

class NatsConsumerTest {

    /** Records what the consumer did with a message. */
    private static final class FakeMessage implements NatsConsumer.Settlement {
        final List<String> calls = new ArrayList<>();

        @Override
        public void ack() {
            this.calls.add("ack");
        }

        @Override
        public void nakWithDelay(Duration delay) {
            this.calls.add("nak " + delay.toSeconds() + "s");
        }
    }

    @Test
    void theAuditConsumerHasOneMessageInFlightSoTheChannelKeepsTheOrder() {
        ConsumerConfiguration configuration = NatsConsumer.consumerConfiguration("discord-audit", new String[]{"staff.action"}, 1,
                Duration.ofMinutes(10));

        assertEquals(1, configuration.getMaxAckPending(), "one unacknowledged message at a time");
        assertEquals(Duration.ofMinutes(10), configuration.getAckWait(), "a slow webhook is not redelivered in the middle");
        assertEquals(List.of("blackhole.events.staff.action"), configuration.getFilterSubjects(), "only staff actions");
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
