package net.onelitefeather.blackhole.discord.notify;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import net.onelitefeather.blackhole.discord.config.BotConfig.NatsSettings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The NATS JetStream adapter: pulls the report, appeal and case events from a durable consumer on the
 * backend's stream {@code BLACKHOLE_EVENTS} and hands each body to the {@link NotificationDispatcher} (or
 * another handler). It holds no logic of its own beyond settling: done and drop are acknowledged, a retry is
 * naked with a delay. The stream keeps messages for seven days, so a bot that is down for a while catches up.
 * If the server is unreachable (or the backend has not created the stream yet) the bot keeps serving
 * commands and tries again every 30 seconds; once connected, jnats reconnects by itself.
 */
public final class NatsConsumer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NatsConsumer.class);

    /** The stream the backend creates and owns. */
    public static final String STREAM = "BLACKHOLE_EVENTS";
    /** Every event subject starts with this; the rest is the event type. */
    public static final String SUBJECT_PREFIX = "blackhole.events.";
    public static final String NOTIFY_DURABLE = "blackhole-discord-notify";
    public static final String CASE_DURABLE = "blackhole-discord-case";
    static final Duration RETRY_DELAY = Duration.ofSeconds(10);
    static final Duration RECONNECT_DELAY = Duration.ofSeconds(30);
    private static final Duration ACK_WAIT = Duration.ofSeconds(30);
    private static final Duration FETCH_WAIT = Duration.ofSeconds(1);
    private static final long PULL_RETRY_SECONDS = 5;
    private static final int IN_FLIGHT = 5;

    /** What can be done with one message; the adapter around a real {@code Message}, or a fake in tests. */
    interface Settlement {
        void ack();

        void nakWithDelay(Duration delay);
    }

    private final NatsSettings settings;
    private final String durable;
    private final String[] eventTypes;
    private final Function<byte[], NotificationHandler.Outcome> handler;
    private final ScheduledExecutorService scheduler;
    private final Duration retryDelay;
    private final Duration reconnectDelay;
    private final int inFlight;
    private final Duration ackWait;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile Connection connection;
    private volatile Thread puller;

    /** The report and appeal notifications of the team channel. */
    public NatsConsumer(@NotNull NatsSettings settings, @NotNull NotificationDispatcher dispatcher, @NotNull ScheduledExecutorService scheduler) {
        this(settings, NOTIFY_DURABLE, NotificationEvent.ROUTING_KEYS, dispatcher::onMessage, scheduler);
    }

    /**
     * Any durable consumer on the events stream.
     *
     * @param durable    the durable consumer name, created or updated by the bot
     * @param eventTypes the event types to receive (the subject is the prefix plus the type)
     * @param handler    decides what happens with a body: done, retry later or drop
     */
    public NatsConsumer(@NotNull NatsSettings settings, @NotNull String durable, @NotNull String[] eventTypes,
                        @NotNull Function<byte[], NotificationHandler.Outcome> handler, @NotNull ScheduledExecutorService scheduler) {
        this(settings, durable, eventTypes, handler, scheduler, RETRY_DELAY, RECONNECT_DELAY);
    }

    /**
     * A durable consumer that works through its messages one at a time, for handlers whose order matters and that may take
     * longer than the default ack wait (the staff audit posts).
     *
     * @param inFlight how many messages are unacknowledged at a time; one keeps the order
     * @param ackWait  how long a message may stay unacknowledged before it is redelivered
     */
    public NatsConsumer(@NotNull NatsSettings settings, @NotNull String durable, @NotNull String[] eventTypes,
                        @NotNull Function<byte[], NotificationHandler.Outcome> handler, @NotNull ScheduledExecutorService scheduler,
                        int inFlight, @NotNull Duration ackWait) {
        this(settings, durable, eventTypes, handler, scheduler, RETRY_DELAY, RECONNECT_DELAY, inFlight, ackWait);
    }

    NatsConsumer(@NotNull NatsSettings settings, @NotNull String durable, @NotNull String[] eventTypes,
                 @NotNull Function<byte[], NotificationHandler.Outcome> handler, @NotNull ScheduledExecutorService scheduler,
                 @NotNull Duration retryDelay, @NotNull Duration reconnectDelay) {
        this(settings, durable, eventTypes, handler, scheduler, retryDelay, reconnectDelay, IN_FLIGHT, ACK_WAIT);
    }

    private NatsConsumer(NatsSettings settings, String durable, String[] eventTypes,
                         Function<byte[], NotificationHandler.Outcome> handler, ScheduledExecutorService scheduler,
                         Duration retryDelay, Duration reconnectDelay, int inFlight, Duration ackWait) {
        this.settings = settings;
        this.durable = durable;
        this.eventTypes = eventTypes.clone();
        this.handler = handler;
        this.scheduler = scheduler;
        this.retryDelay = retryDelay;
        this.reconnectDelay = reconnectDelay;
        this.inFlight = inFlight;
        this.ackWait = ackWait;
    }

    /** The durable consumer: explicit acks, five unacknowledged at a time, new messages on first creation. */
    static ConsumerConfiguration consumerConfiguration(String durable, String[] eventTypes) {
        return consumerConfiguration(durable, eventTypes, IN_FLIGHT, ACK_WAIT);
    }

    static ConsumerConfiguration consumerConfiguration(String durable, String[] eventTypes, int inFlight, Duration ackWait) {
        List<String> subjects = Arrays.stream(eventTypes).map(type -> SUBJECT_PREFIX + type).toList();
        return ConsumerConfiguration.builder()
                .durable(durable)
                .filterSubjects(subjects)
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(ackWait)
                .maxAckPending(inFlight)
                .maxDeliver(-1)
                .deliverPolicy(DeliverPolicy.New)
                .build();
    }

    /** Done and drop are acknowledged; a retry goes back to the consumer after {@code retryDelay}. */
    static void settle(NotificationHandler.Outcome outcome, Settlement message, Duration retryDelay) {
        if (outcome == NotificationHandler.Outcome.RETRY) {
            message.nakWithDelay(retryDelay);
        } else {
            message.ack();
        }
    }

    /** Completes once the consumer is bound and pulling (for health checks and tests). */
    public CompletableFuture<Void> ready() {
        return this.ready;
    }

    /** Starts connecting in the background; returns at once. */
    public void start() {
        this.scheduler.execute(this::connectOrRetry);
    }

    private void connectOrRetry() {
        if (this.closed.getCount() == 0 || this.connection != null) {
            return;
        }
        try {
            connect();
        } catch (Exception e) {
            LOGGER.error("Could not start consumer {} on NATS at {} ({}); trying again in {} seconds",
                    this.durable, NatsSettings.redact(this.settings.url()), e.getMessage(), this.reconnectDelay.toSeconds());
            this.scheduler.schedule(this::connectOrRetry, this.reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void connect() throws Exception {
        Connection opened = Nats.connect(options(this.settings));
        JetStreamSubscription subscription;
        try {
            opened.jetStreamManagement().addOrUpdateConsumer(STREAM,
                    consumerConfiguration(this.durable, this.eventTypes, this.inFlight, this.ackWait));
            subscription = opened.jetStream().subscribe(null, PullSubscribeOptions.bind(STREAM, this.durable));
        } catch (Exception e) {
            opened.close();
            throw e;
        }
        this.connection = opened;
        Thread thread = Thread.ofPlatform().daemon().name("nats-pull-" + this.durable).unstarted(() -> pull(subscription));
        this.puller = thread;
        thread.start();
        this.ready.complete(null);
        LOGGER.info("Consuming events with the durable consumer {}", this.durable);
    }

    static Options options(NatsSettings settings) {
        Options.Builder builder = new Options.Builder()
                .servers(settings.url().split("\\s*,\\s*"))
                .connectionName("blackhole-discord")
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(2))
                .connectionListener(LOG_CONNECTION)
                .errorListener(LOG_ERRORS);
        String creds = settings.credsFile();
        if (creds != null && !creds.isBlank()) {
            if (Files.isReadable(Path.of(creds))) {
                builder.credentialPath(creds);
            } else {
                LOGGER.error("The NATS credentials file {} is missing or unreadable; connecting without credentials", creds);
            }
        }
        return builder.build();
    }

    private void pull(JetStreamSubscription subscription) {
        while (this.closed.getCount() != 0) {
            try {
                for (Message message : subscription.fetch(this.inFlight, FETCH_WAIT)) {
                    NotificationHandler.Outcome outcome;
                    try {
                        outcome = this.handler.apply(message.getData());
                    } catch (RuntimeException e) {
                        LOGGER.error("The handler of {} failed unexpectedly; the event comes back later", this.durable, e);
                        outcome = NotificationHandler.Outcome.RETRY;
                    }
                    settle(outcome, settlement(message), this.retryDelay);
                }
            } catch (Exception e) {
                if (this.closed.getCount() == 0) {
                    return;
                }
                LOGGER.warn("Pulling for {} failed ({}); trying again in {} seconds", this.durable, e.getMessage(), PULL_RETRY_SECONDS);
                try {
                    if (this.closed.await(PULL_RETRY_SECONDS, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static Settlement settlement(Message message) {
        return new Settlement() {
            @Override
            public void ack() {
                message.ack();
            }

            @Override
            public void nakWithDelay(Duration delay) {
                message.nakWithDelay(delay);
            }
        };
    }

    @Override
    public void close() {
        this.closed.countDown();
        Thread thread = this.puller;
        if (thread != null) {
            thread.interrupt();
        }
        Connection current = this.connection;
        if (current != null) {
            try {
                current.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOGGER.debug("Closing the NATS connection failed: {}", e.getMessage());
            }
        }
    }

    private static final ConnectionListener LOG_CONNECTION = (conn, type) -> LOGGER.info("NATS connection event: {}", type);

    private static final ErrorListener LOG_ERRORS = new ErrorListener() {
        @Override
        public void errorOccurred(Connection conn, String error) {
            LOGGER.warn("NATS error: {}", error);
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exp) {
            LOGGER.warn("NATS exception: {}", exp.getMessage());
        }
    };
}
