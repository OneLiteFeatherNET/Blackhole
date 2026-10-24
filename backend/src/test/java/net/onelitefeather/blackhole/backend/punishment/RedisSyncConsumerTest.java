package net.onelitefeather.blackhole.backend.punishment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.EventConsumers;
import net.onelitefeather.blackhole.backend.support.FakeEventSubscriptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSyncConsumerTest {

    private final FakeEventSubscriptions subscriptions = new FakeEventSubscriptions();
    private final List<DomainEvent> handled = new ArrayList<>();
    private boolean failing;
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RedisSyncConsumer.class);

    private final PunishmentSyncHandler handler = new PunishmentSyncHandler(null) {
        @Override
        public void handle(DomainEvent event) {
            RedisSyncConsumerTest.this.handled.add(event);
            if (RedisSyncConsumerTest.this.failing) {
                throw new IllegalStateException("redis down");
            }
        }
    };

    private final RedisSyncConsumer consumer = new RedisSyncConsumer(this.subscriptions, this.handler);

    @BeforeEach
    void captureLog() {
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void releaseLog() {
        this.logger.detachAppender(this.log);
    }

    private static DomainEvent event() {
        return new DomainEvent("id-1", "punishment.created", 1L, 1, Map.of("owner", "abc"));
    }

    @Test
    void startingSubscribesTheRedisSyncConsumer() {
        this.consumer.start();

        assertEquals(List.of(EventConsumers.REDIS_SYNC), this.subscriptions.subscribed());
    }

    @Test
    void aDeliveredEventReachesTheSyncHandler() throws Exception {
        this.consumer.start();

        this.subscriptions.deliver(event());

        assertEquals(List.of(event()), this.handled);
    }

    @Test
    void aFailingHandlerIsLoggedAndTheDeliveryStillCompletesSoTheMessageIsAcked() {
        this.consumer.start();
        this.failing = true;

        assertDoesNotThrow(() -> this.subscriptions.deliver(event()),
                "the handler must not throw: the adapter acks when it returns");

        assertTrue(this.log.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR),
                "the failure must be logged, got " + this.log.list);
    }

    @Test
    void stoppingClosesTheSubscription() {
        this.consumer.start();

        this.consumer.stop();

        assertTrue(this.subscriptions.closed());
    }
}
