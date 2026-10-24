package net.onelitefeather.blackhole.backend.events;

import com.rabbitmq.client.Channel;
import io.micronaut.json.JsonMapper;
import io.micronaut.rabbitmq.connect.ChannelPool;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * {@link EventSubscriptionFactory} over a RabbitMQ durable queue (the raw client, manual ack). The
 * queue is declared and bound by {@link BlackholeRabbitTopology}; its name is the consumer name
 * with dashes turned into dots ({@code blackhole-redis-sync} is {@code blackhole.redis.sync}).
 * Every message is acknowledged after one attempt, whether the handler succeeded or not.
 */
@Singleton
public class RabbitEventSubscriptionFactory implements EventSubscriptionFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitEventSubscriptionFactory.class);

    private final ChannelPool channelPool;
    private final JsonMapper jsonMapper;

    public RabbitEventSubscriptionFactory(@Named("default") ChannelPool channelPool, JsonMapper jsonMapper) {
        this.channelPool = channelPool;
        this.jsonMapper = jsonMapper;
    }

    static String queueName(EventConsumer consumer) {
        return consumer.name().replace('-', '.');
    }

    @Override
    public EventSubscription subscribe(EventConsumer consumer, EventHandler handler) {
        try {
            Channel channel = this.channelPool.getChannel();
            String tag = channel.basicConsume(queueName(consumer), false, (consumerTag, delivery) -> {
                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                try {
                    handler.handle(this.jsonMapper.readValue(delivery.getBody(), DomainEvent.class));
                } catch (Exception e) {
                    LOGGER.error("Failed to process a domain event for {}: {}", consumer.name(), e.getMessage());
                }
                channel.basicAck(deliveryTag, false);
            }, consumerTag -> {
                // no-op cancel callback
            });
            return () -> {
                try {
                    channel.basicCancel(tag);
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Failed to cancel the consumer {}: {}", consumer.name(), e.getMessage());
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to subscribe " + consumer.name() + ": " + e.getMessage(), e);
        }
    }
}
