package net.onelitefeather.blackhole.backend.punishment;

import com.rabbitmq.client.Channel;
import io.micronaut.json.JsonMapper;
import io.micronaut.rabbitmq.connect.ChannelPool;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.RabbitTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Mirrors active-punishment state into Redis so every Velocity proxy in a multi-proxy network
 * sees consistent ban/mute state without querying the backend on every login/chat message.
 * Consumes {@code punishment.created}/{@code updated}/{@code expired}/{@code revoked} and
 * {@code appeal.resolved} off the shared {@link RabbitTopology#REDIS_SYNC_QUEUE} - a shared
 * (not per-replica) queue, since Redis is the one cross-proxy source of truth and each event
 * must be processed exactly once.
 *
 * <p>Uses the raw RabbitMQ Java client (not {@code @RabbitListener}). This is an explicitly
 * best-effort side channel with no retry loop: a missed write self-heals at the next mutation on
 * the same profile, and every proxy falls back to an HTTP call on a cache miss, so a message is
 * always acked after a single attempt rather than redelivered.</p>
 */
@Singleton
public class RedisSyncConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSyncConsumer.class);

    private final ChannelPool channelPool;
    private final JsonMapper jsonMapper;
    private final PunishmentSyncHandler handler;

    public RedisSyncConsumer(@Named("default") ChannelPool channelPool, JsonMapper jsonMapper, PunishmentSyncHandler handler) {
        this.channelPool = channelPool;
        this.jsonMapper = jsonMapper;
        this.handler = handler;
    }

    @EventListener
    void onStartup(ApplicationStartupEvent event) {
        try {
            Channel channel = this.channelPool.getChannel();
            channel.basicConsume(RabbitTopology.REDIS_SYNC_QUEUE, false, (consumerTag, delivery) -> {
                long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                try {
                    handle(delivery.getBody());
                } catch (Exception e) {
                    LOGGER.error("Failed to process domain event for Redis sync: {}", e.getMessage());
                }
                channel.basicAck(deliveryTag, false);
            }, consumerTag -> {
                // no-op cancel callback
            });
            LOGGER.info("Listening for domain events to sync active punishment state into Redis");
        } catch (IOException e) {
            LOGGER.error("Failed to set up Redis sync consumer: {}", e.getMessage());
        }
    }

    private void handle(byte[] body) throws IOException {
        this.handler.handle(this.jsonMapper.readValue(body, DomainEvent.class));
    }
}
