package net.onelitefeather.blackhole.backend.rabbitmq;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import io.micronaut.rabbitmq.connect.ChannelInitializer;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Singleton // (1)
public class ChannelPoolListener extends ChannelInitializer { // (2)

    @Override
    public void initialize(Channel channel, String name) throws IOException { // (3)

        // 1) Deklariere Exchange für Dead-Letter
        channel.exchangeDeclare(
                "punish.deadletter.exchange",   // Exchange-Name
                BuiltinExchangeType.DIRECT,     // Exchange-Typ (direct)
                true                            // durable
        );

        // 2) Deklariere Queue "punish-entry" mit Dead-Letter-Argumenten
        Map<String, Object> punishEntryArgs = new HashMap<>();
        punishEntryArgs.put("x-dead-letter-exchange", "punish.deadletter.exchange");
        punishEntryArgs.put("x-dead-letter-routing-key", "punish-entry-expired");

        channel.queueDeclare(
                "punish-entry",     // Queue-Name
                true,               // durable
                false,              // exclusive
                false,              // autoDelete
                punishEntryArgs     // arguments
        );

        // 3) Deklariere Queue "punish-entry-expired"
        channel.queueDeclare(
                "punish-entry-expired", // Queue-Name
                true,                   // durable
                false,                  // exclusive
                false,                  // autoDelete
                null                    // keine speziellen Argumente
        );

        // 4) Binde Queue "punish-entry-expired" an die Dead-Letter-Exchange
        channel.queueBind(
                "punish-entry-expired",     // queue
                "punish.deadletter.exchange", // exchange
                "punish-entry-expired"      // routing key
        );
    }
}