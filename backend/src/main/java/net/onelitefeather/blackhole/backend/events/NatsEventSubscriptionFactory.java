package net.onelitefeather.blackhole.backend.events;

import io.micronaut.json.JsonMapper;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link EventSubscriptionFactory} over a JetStream durable pull consumer. Every instance that binds
 * the same durable name shares the work: the server hands each message to one puller. One thread per
 * subscription pulls small batches; when NATS is unreachable it logs and tries again, forever, until
 * the subscription is closed. Messages are acknowledged after the handler returned or failed.
 */
@Singleton
public class NatsEventSubscriptionFactory implements EventSubscriptionFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(NatsEventSubscriptionFactory.class);
    private static final int BATCH = 10;
    private static final Duration FETCH_WAIT = Duration.ofSeconds(1);
    private static final long RETRY_SECONDS = 5;

    private final NatsConnectionProvider connections;
    private final StreamProvisioner provisioner;
    private final JsonMapper mapper;

    public NatsEventSubscriptionFactory(NatsConnectionProvider connections, StreamProvisioner provisioner, JsonMapper mapper) {
        this.connections = connections;
        this.provisioner = provisioner;
        this.mapper = mapper;
    }

    @Override
    public EventSubscription subscribe(EventConsumer consumer, EventHandler handler) {
        PullLoop loop = new PullLoop(consumer, handler);
        loop.start();
        return loop;
    }

    private final class PullLoop implements EventSubscription, Runnable {

        private final EventConsumer consumer;
        private final EventHandler handler;
        private final CountDownLatch closed = new CountDownLatch(1);
        private Thread thread;

        PullLoop(EventConsumer consumer, EventHandler handler) {
            this.consumer = consumer;
            this.handler = handler;
        }

        void start() {
            this.thread = Thread.ofPlatform().daemon().name("nats-consumer-" + this.consumer.name()).start(this);
        }

        private boolean isClosed() {
            return this.closed.getCount() == 0;
        }

        @Override
        public void run() {
            while (!isClosed()) {
                try {
                    pullUntilClosed();
                } catch (Exception e) {
                    if (isClosed()) {
                        return;
                    }
                    LOGGER.warn("Consumer {} lost its NATS subscription, retrying in {}s: {}",
                            this.consumer.name(), RETRY_SECONDS, e.getMessage());
                    if (awaitClosed()) {
                        return;
                    }
                }
            }
        }

        private boolean awaitClosed() {
            try {
                return this.closed.await(RETRY_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }

        private void pullUntilClosed() throws Exception {
            if (!NatsEventSubscriptionFactory.this.provisioner.ensureStream()) {
                throw new IllegalStateException("the stream " + NatsStreams.STREAM + " is not available");
            }
            NatsEventSubscriptionFactory.this.provisioner.ensureConsumer(this.consumer);
            JetStreamSubscription subscription = NatsEventSubscriptionFactory.this.connections.await(Duration.ofSeconds(10)).jetStream()
                    .subscribe(null, PullSubscribeOptions.bind(NatsStreams.STREAM, this.consumer.name()));
            LOGGER.info("Consumer {} is pulling from {}", this.consumer.name(), NatsStreams.STREAM);
            try {
                while (!isClosed()) {
                    List<Message> messages = subscription.fetch(BATCH, FETCH_WAIT);
                    for (Message message : messages) {
                        NatsDelivery.deliver(message.getData(), NatsEventSubscriptionFactory.this.mapper,
                                this.consumer.name(), this.handler, message::ack);
                    }
                }
            } finally {
                try {
                    subscription.unsubscribe();
                } catch (RuntimeException ignored) {
                    // the connection may already be gone
                }
            }
        }

        @Override
        public void close() {
            this.closed.countDown();
            if (this.thread != null) {
                this.thread.interrupt();
            }
        }
    }
}
