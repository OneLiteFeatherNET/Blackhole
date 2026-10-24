package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.EventConsumers;
import net.onelitefeather.blackhole.backend.events.EventSubscription;
import net.onelitefeather.blackhole.backend.events.EventSubscriptionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors active-punishment state into Redis so every Velocity proxy in a multi-proxy network
 * sees consistent ban/mute state without querying the backend on every login/chat message.
 * Consumes {@code punishment.created}/{@code updated}/{@code expired}/{@code revoked} and
 * {@code appeal.resolved} through {@link EventConsumers#REDIS_SYNC} - one durable consumer shared
 * by all backend replicas (not per replica), since Redis is the one cross-proxy source of truth
 * and each event must be processed exactly once.
 *
 * <p>This is an explicitly best-effort side channel with no retry loop: a missed write self-heals
 * at the next mutation on the same profile, and every proxy falls back to an HTTP call on a cache
 * miss, so a handler failure is logged and the message is acknowledged after a single attempt
 * rather than redelivered.</p>
 */
@Singleton
public class RedisSyncConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSyncConsumer.class);

    private final EventSubscriptionFactory subscriptions;
    private final PunishmentSyncHandler handler;
    private EventSubscription subscription;

    public RedisSyncConsumer(EventSubscriptionFactory subscriptions, PunishmentSyncHandler handler) {
        this.subscriptions = subscriptions;
        this.handler = handler;
    }

    @EventListener
    void onStartup(ApplicationStartupEvent event) {
        start();
    }

    void start() {
        try {
            this.subscription = this.subscriptions.subscribe(EventConsumers.REDIS_SYNC, this::handle);
            LOGGER.info("Listening for domain events to sync active punishment state into Redis");
        } catch (RuntimeException e) {
            LOGGER.error("Failed to set up Redis sync consumer: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (this.subscription != null) {
            this.subscription.close();
            this.subscription = null;
        }
    }

    private void handle(DomainEvent event) {
        try {
            this.handler.handle(event);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to process domain event for Redis sync: {}", e.getMessage());
        }
    }
}
