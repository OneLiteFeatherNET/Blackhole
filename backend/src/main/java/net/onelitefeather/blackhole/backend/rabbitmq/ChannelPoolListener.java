package net.onelitefeather.blackhole.backend.rabbitmq;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import io.micronaut.rabbitmq.connect.ChannelInitializer;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class ChannelPoolListener extends ChannelInitializer {
    
    private static final String BLACK_HOLE_EXCHANGE = "blackhole.exchange";
    private static final String PUNISH_ENTRY_QUEUE = "punish-entry";
    private static final String PUNISH_ENTRY_EXPIRED_QUEUE = "punish-entry-expired";

    @Override
    public void initialize(Channel channel, String name) throws IOException { // (3)

        // 1) Deklariere Exchange für Dead-Letter
        channel.exchangeDeclare(
                BLACK_HOLE_EXCHANGE,   // Exchange-Name
                BuiltinExchangeType.DIRECT,     // Exchange-Typ (direct)
                true                            // durable
        );

        Map<String, Object> punishEntryArgs = new HashMap<>();
        punishEntryArgs.put("x-dead-letter-exchange", BLACK_HOLE_EXCHANGE);
        punishEntryArgs.put("x-dead-letter-routing-key", PUNISH_ENTRY_EXPIRED_QUEUE);

        channel.queueDeclare(
                PUNISH_ENTRY_QUEUE,     // Queue-Name
                true,               // durable
                false,              // exclusive
                false,              // autoDelete
                punishEntryArgs     // arguments
        );

        // 3) Deklariere Queue "punish-entry-expired"
        channel.queueDeclare(
                PUNISH_ENTRY_EXPIRED_QUEUE, // Queue-Name
                true,                   // durable
                false,                  // exclusive
                false,                  // autoDelete
                null                    // keine speziellen Argumente
        );

        // 4) Binde Queue "punish-entry-expired" an die Dead-Letter-Exchange
        channel.queueBind(
                PUNISH_ENTRY_EXPIRED_QUEUE,     // queue
                BLACK_HOLE_EXCHANGE, // exchange
                PUNISH_ENTRY_EXPIRED_QUEUE      // routing key
        );
        channel.queueBind(
                PUNISH_ENTRY_QUEUE,     // queue
                BLACK_HOLE_EXCHANGE,
                PUNISH_ENTRY_QUEUE
        );
    }
}