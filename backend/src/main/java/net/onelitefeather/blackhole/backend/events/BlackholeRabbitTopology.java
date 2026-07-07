package net.onelitefeather.blackhole.backend.events;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import io.micronaut.context.annotation.Value;
import io.micronaut.rabbitmq.connect.ChannelInitializer;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.Map;

/**
 * Declares the exchanges/queues Blackhole publishes to and consumes from. Declaration is
 * idempotent in RabbitMQ, so re-running this on every channel creation (which Micronaut RabbitMQ
 * does per pooled channel) is safe and cheap.
 */
@Singleton
public class BlackholeRabbitTopology extends ChannelInitializer {

    private final int webhookRetryDelayMs;

    public BlackholeRabbitTopology(@Value("${blackhole.webhook.retry-delay-ms:30000}") int webhookRetryDelayMs) {
        this.webhookRetryDelayMs = webhookRetryDelayMs;
    }

    @Override
    public void initialize(Channel channel, String name) throws IOException {
        channel.exchangeDeclare(RabbitTopology.EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true);
        channel.exchangeDeclare(RabbitTopology.CACHE_INVALIDATE_EXCHANGE, BuiltinExchangeType.FANOUT, true);

        channel.queueDeclare(
                RabbitTopology.WEBHOOK_DISPATCH_QUEUE, true, false, false,
                Map.of(
                        "x-dead-letter-exchange", RabbitTopology.WEBHOOK_RETRY_EXCHANGE,
                        "x-dead-letter-routing-key", RabbitTopology.WEBHOOK_RETRY_ROUTING_KEY
                )
        );
        channel.queueBind(RabbitTopology.WEBHOOK_DISPATCH_QUEUE, RabbitTopology.EVENTS_EXCHANGE, "#");

        channel.exchangeDeclare(RabbitTopology.WEBHOOK_RETRY_EXCHANGE, BuiltinExchangeType.DIRECT, true);
        channel.queueDeclare(
                RabbitTopology.WEBHOOK_RETRY_QUEUE, true, false, false,
                Map.of(
                        "x-message-ttl", this.webhookRetryDelayMs,
                        "x-dead-letter-exchange", "",
                        "x-dead-letter-routing-key", RabbitTopology.WEBHOOK_DISPATCH_QUEUE
                )
        );
        channel.queueBind(RabbitTopology.WEBHOOK_RETRY_QUEUE, RabbitTopology.WEBHOOK_RETRY_EXCHANGE, RabbitTopology.WEBHOOK_RETRY_ROUTING_KEY);

        channel.queueDeclare(RabbitTopology.WEBHOOK_FAILED_QUEUE, true, false, false, null);
    }
}
