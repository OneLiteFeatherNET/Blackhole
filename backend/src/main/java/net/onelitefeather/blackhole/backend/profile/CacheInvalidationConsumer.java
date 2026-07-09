package net.onelitefeather.blackhole.backend.profile;

import com.rabbitmq.client.Channel;
import io.micronaut.json.JsonMapper;
import io.micronaut.rabbitmq.connect.ChannelPool;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.RabbitTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Every replica declares its own exclusive, auto-delete queue bound to the
 * {@code blackhole.cache.invalidate} fanout exchange at startup, so a write on any one replica
 * evicts the {@link ProfileCache} entry on every other replica too.
 *
 * <p>This uses the RabbitMQ Java client directly rather than Micronaut's declarative
 * {@code @RabbitListener}/{@code @Queue}, since those expect a fixed, shared queue name — wrong
 * here, where every instance needs its own private queue.</p>
 */
@Singleton
public class CacheInvalidationConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationConsumer.class);

    private final ChannelPool channelPool;
    private final ProfileCache profileCache;
    private final JsonMapper jsonMapper;

    public CacheInvalidationConsumer(@Named("default") ChannelPool channelPool, ProfileCache profileCache, JsonMapper jsonMapper) {
        this.channelPool = channelPool;
        this.profileCache = profileCache;
        this.jsonMapper = jsonMapper;
    }

    @EventListener
    void onStartup(ApplicationStartupEvent event) {
        try {
            Channel channel = this.channelPool.getChannel();
            String queueName = channel.queueDeclare("", false, true, true, null).getQueue();
            channel.queueBind(queueName, RabbitTopology.CACHE_INVALIDATE_EXCHANGE, "");
            channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
                try {
                    CacheInvalidationMessage message = this.jsonMapper.readValue(delivery.getBody(), CacheInvalidationMessage.class);
                    this.profileCache.invalidate(message.owner());
                } catch (IOException e) {
                    LOGGER.error("Failed to process cache invalidation message: {}", e.getMessage());
                }
            }, consumerTag -> {
                // no-op cancel callback
            });
            LOGGER.info("Listening for cross-replica cache invalidation on queue {}", queueName);
        } catch (IOException e) {
            LOGGER.error("Failed to set up cache invalidation consumer: {}", e.getMessage());
        }
    }
}
