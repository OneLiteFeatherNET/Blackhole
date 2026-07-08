package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.EventSubscriptionEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface EventSubscriptionRepository extends PageableRepository<EventSubscriptionEntity, UUID> {

    Page<EventSubscriptionEntity> findByConnectorIdentifier(UUID connectorIdentifier, Pageable pageable);

    /**
     * All active subscriptions. {@code eventTypes} membership is filtered in-memory by
     * {@code WebhookDispatchConsumer} rather than in this query - connector/subscription counts
     * are small, and deriving a "list contains" predicate isn't a query shape this codebase has
     * exercised elsewhere.
     */
    List<EventSubscriptionEntity> findByActiveTrue();
}
