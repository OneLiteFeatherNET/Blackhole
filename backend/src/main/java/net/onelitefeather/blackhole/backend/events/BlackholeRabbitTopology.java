package net.onelitefeather.blackhole.backend.events;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import io.micronaut.rabbitmq.connect.ChannelInitializer;
import jakarta.inject.Singleton;

import java.io.IOException;

/**
 * Declares the exchanges/queues Blackhole publishes to and consumes from. Declaration is
 * idempotent in RabbitMQ, so re-running this on every channel creation (which Micronaut RabbitMQ
 * does per pooled channel) is safe and cheap.
 */
@Singleton
public class BlackholeRabbitTopology extends ChannelInitializer {

    @Override
    public void initialize(Channel channel, String name) throws IOException {
        channel.exchangeDeclare(RabbitTopology.EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true);
        channel.exchangeDeclare(RabbitTopology.CACHE_INVALIDATE_EXCHANGE, BuiltinExchangeType.FANOUT, true);

        channel.queueDeclare(RabbitTopology.REDIS_SYNC_QUEUE, true, false, false, null);
        channel.queueBind(RabbitTopology.REDIS_SYNC_QUEUE, RabbitTopology.EVENTS_EXCHANGE, "punishment.created");
        channel.queueBind(RabbitTopology.REDIS_SYNC_QUEUE, RabbitTopology.EVENTS_EXCHANGE, "punishment.expired");
        channel.queueBind(RabbitTopology.REDIS_SYNC_QUEUE, RabbitTopology.EVENTS_EXCHANGE, "punishment.revoked");
        channel.queueBind(RabbitTopology.REDIS_SYNC_QUEUE, RabbitTopology.EVENTS_EXCHANGE, "appeal.resolved");
    }
}
