package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.runtime.event.ApplicationStartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEvent;
import net.onelitefeather.blackhole.backend.events.EventSubscription;
import net.onelitefeather.blackhole.backend.events.EventSubscriptionFactory;
import net.onelitefeather.blackhole.backend.ipban.IpBanEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Rebuilds this replica's feeds when any replica changes an IP ban. Every replica needs every change, so each one takes an
 * ephemeral subscription: the server keeps no state for it, and a replica that goes away (a rollout, a crash) leaves nothing
 * behind. An event that arrives while the subscription is reconnecting is lost; the marker check of {@link FeedCache} catches
 * that change on its next tick.
 */
@Singleton
public class FeedChangeConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeedChangeConsumer.class);

    private final EventSubscriptionFactory subscriptions;
    private final FeedCache cache;
    private EventSubscription subscription;

    public FeedChangeConsumer(EventSubscriptionFactory subscriptions, FeedCache cache) {
        this.subscriptions = subscriptions;
        this.cache = cache;
    }

    @EventListener
    void onStartup(ApplicationStartupEvent event) {
        start();
    }

    void start() {
        try {
            this.subscription = this.subscriptions.subscribeEphemeral(List.of(IpBanEvents.CHANGED), this::handle);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to set up the firewall feed change subscription: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (this.subscription != null) {
            this.subscription.close();
            this.subscription = null;
        }
    }

    void handle(DomainEvent event) {
        if (IpBanEvents.CHANGED.equals(event.eventType())) {
            this.cache.changed();
        }
    }
}
