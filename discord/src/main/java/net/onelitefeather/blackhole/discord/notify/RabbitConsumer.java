package net.onelitefeather.blackhole.discord.notify;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import net.onelitefeather.blackhole.discord.config.BotConfig.RabbitSettings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The RabbitMQ adapter: consumes the report and appeal events from a durable queue bound to the
 * backend's {@code blackhole.events} topic exchange and hands each body to the {@link NotificationDispatcher}.
 * It holds no logic of its own beyond acknowledging. A message the dispatcher cannot handle yet goes back
 * to the queue after a delay; the queue keeps messages for seven days, so a bot that is down for a while
 * catches up. If the broker is unreachable the bot keeps serving commands and tries again every 30 seconds.
 */
public final class RabbitConsumer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitConsumer.class);

    public static final String EXCHANGE = "blackhole.events";
    public static final String QUEUE = "blackhole.discord.notify";
    static final Duration RETRY_DELAY = Duration.ofSeconds(10);
    static final Duration RECONNECT_DELAY = Duration.ofSeconds(30);
    private static final long QUEUE_TTL_MILLIS = Duration.ofDays(7).toMillis();

    private final RabbitSettings settings;
    private final String queue;
    private final String[] routingKeys;
    private final Function<byte[], NotificationHandler.Outcome> handler;
    private final ScheduledExecutorService scheduler;
    private volatile Connection connection;

    /** The report and appeal notifications of the team channel. */
    public RabbitConsumer(@NotNull RabbitSettings settings, @NotNull NotificationDispatcher dispatcher, @NotNull ScheduledExecutorService scheduler) {
        this(settings, QUEUE, NotificationEvent.ROUTING_KEYS, dispatcher::onMessage, scheduler);
    }

    /**
     * Any durable queue bound to the topic exchange.
     *
     * @param queue       the queue to declare and consume
     * @param routingKeys what to bind it to
     * @param handler     decides what happens with a body: done, retry later or drop
     */
    public RabbitConsumer(@NotNull RabbitSettings settings, @NotNull String queue, @NotNull String[] routingKeys,
                          @NotNull Function<byte[], NotificationHandler.Outcome> handler, @NotNull ScheduledExecutorService scheduler) {
        this.settings = settings;
        this.queue = queue;
        this.routingKeys = routingKeys.clone();
        this.handler = handler;
        this.scheduler = scheduler;
    }

    /** Starts connecting in the background; returns at once. */
    public void start() {
        this.scheduler.execute(this::connectOrRetry);
    }

    private void connectOrRetry() {
        try {
            connect();
        } catch (Exception e) {
            LOGGER.error("Could not connect to RabbitMQ at {}:{} ({}); trying again in {} seconds",
                    this.settings.host(), this.settings.port(), e.getMessage(), RECONNECT_DELAY.toSeconds());
            this.scheduler.schedule(this::connectOrRetry, RECONNECT_DELAY.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private void connect() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(this.settings.host());
        factory.setPort(this.settings.port());
        factory.setUsername(this.settings.user());
        factory.setPassword(this.settings.password());
        factory.setVirtualHost(this.settings.vhost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        Connection opened = factory.newConnection("blackhole-discord");
        try {
            Channel channel = opened.createChannel();
            // Declarations are idempotent and match the backend's exchange.
            channel.exchangeDeclare(EXCHANGE, BuiltinExchangeType.TOPIC, true);
            channel.queueDeclare(this.queue, true, false, false, Map.of("x-message-ttl", QUEUE_TTL_MILLIS));
            for (String key : this.routingKeys) {
                channel.queueBind(this.queue, EXCHANGE, key);
            }
            channel.basicQos(5);
            DeliverCallback deliver = (tag, delivery) -> {
                NotificationHandler.Outcome outcome = this.handler.apply(delivery.getBody());
                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                if (outcome == NotificationHandler.Outcome.RETRY) {
                    this.scheduler.schedule(() -> settle(channel, deliveryTag, true), RETRY_DELAY.toSeconds(), TimeUnit.SECONDS);
                } else {
                    settle(channel, deliveryTag, false);
                }
            };
            channel.basicConsume(this.queue, false, deliver, tag -> LOGGER.warn("RabbitMQ cancelled the consumer {}", tag));
        } catch (Exception e) {
            opened.abort();
            throw e;
        }
        this.connection = opened;
        LOGGER.info("Consuming events from queue {}", this.queue);
    }

    private static void settle(Channel channel, long deliveryTag, boolean requeue) {
        try {
            if (requeue) {
                channel.basicNack(deliveryTag, false, true);
            } else {
                channel.basicAck(deliveryTag, false);
            }
        } catch (IOException | RuntimeException e) {
            // The channel was lost; RabbitMQ redelivers the unacknowledged message on the next connection.
            LOGGER.warn("Could not settle delivery {}: {}", deliveryTag, e.getMessage());
        }
    }

    @Override
    public void close() {
        Connection current = this.connection;
        if (current != null) {
            try {
                current.close();
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Closing the RabbitMQ connection failed: {}", e.getMessage());
            }
        }
    }
}
