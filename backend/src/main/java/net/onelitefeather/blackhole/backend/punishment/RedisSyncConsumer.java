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
import java.util.Map;

/**
 * Mirrors active-punishment state into Redis so every Velocity proxy in a multi-proxy network
 * sees consistent ban/mute state without querying the backend on every login/chat message.
 * Consumes {@code punishment.created}/{@code expired}/{@code revoked} and
 * {@code appeal.resolved} off the shared {@link RabbitTopology#REDIS_SYNC_QUEUE} - a shared
 * (not per-replica) queue, since Redis is the one cross-proxy source of truth and each event
 * must be processed exactly once.
 *
 * <p>Uses the raw RabbitMQ Java client (not {@code @RabbitListener}), same as
 * {@code WebhookDispatchConsumer}. Unlike that consumer, this is an explicitly best-effort side
 * channel with no retry loop: a missed write self-heals at the next mutation on the same
 * profile, and every proxy falls back to an HTTP call on a cache miss, so a message is always
 * acked after a single attempt rather than redelivered.</p>
 */
@Singleton
public class RedisSyncConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSyncConsumer.class);

    private final ChannelPool channelPool;
    private final JsonMapper jsonMapper;
    private final PunishmentRedisWriter redisWriter;

    public RedisSyncConsumer(@Named("default") ChannelPool channelPool, JsonMapper jsonMapper, PunishmentRedisWriter redisWriter) {
        this.channelPool = channelPool;
        this.jsonMapper = jsonMapper;
        this.redisWriter = redisWriter;
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
        DomainEvent event = this.jsonMapper.readValue(body, DomainEvent.class);
        Map<String, Object> payload = event.payload();
        String owner = String.valueOf(payload.get("owner"));
        String punishmentIdentifier = stringOrNull(payload.get("punishmentIdentifier"));

        switch (event.eventType()) {
            case "punishment.created" -> {
                String type = String.valueOf(payload.get("type"));
                String templateIdentifier = stringOrNull(payload.get("templateIdentifier"));
                Long expiresAt = asLong(payload.get("expiresAt"));
                if ("CHAT".equals(type)) {
                    this.redisWriter.setChatBan(owner, punishmentIdentifier, templateIdentifier, expiresAt);
                } else {
                    this.redisWriter.setBan(owner, type, punishmentIdentifier, templateIdentifier, expiresAt);
                }
            }
            case "punishment.expired", "punishment.revoked" -> clearSlot(owner, String.valueOf(payload.get("type")), punishmentIdentifier);
            case "appeal.resolved" -> {
                if ("GRANTED_FULL_LIFT".equals(String.valueOf(payload.get("decision")))) {
                    clearSlot(owner, String.valueOf(payload.get("type")), punishmentIdentifier);
                }
            }
            default -> LOGGER.debug("Ignoring unrelated event type {} on the Redis sync queue", event.eventType());
        }
    }

    private void clearSlot(String owner, String type, String punishmentIdentifier) {
        if ("CHAT".equals(type)) {
            this.redisWriter.clearChatBan(owner, punishmentIdentifier);
        } else {
            this.redisWriter.clearBan(owner, type, punishmentIdentifier);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
