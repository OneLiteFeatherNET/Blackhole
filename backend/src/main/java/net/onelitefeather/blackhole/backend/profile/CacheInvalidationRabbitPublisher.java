package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.rabbitmq.annotation.Binding;
import io.micronaut.rabbitmq.annotation.RabbitClient;
import net.onelitefeather.blackhole.backend.events.RabbitTopology;

@RabbitClient(RabbitTopology.CACHE_INVALIDATE_EXCHANGE)
public interface CacheInvalidationRabbitPublisher {

    /**
     * Fanout exchanges ignore the routing key, but a value is still required by the client.
     */
    @Binding("")
    void invalidate(CacheInvalidationMessage message);
}
