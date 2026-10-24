package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Publishes the {@code case.*} domain events after a write, in a try/catch: a broker problem never fails the request. */
public final class CaseEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseEventPublisher.class);

    private final DomainEventPublisher publisher;

    public CaseEventPublisher(DomainEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * @param change the non-content change (ids, enums, reason codes); a {@code text} entry is dropped by {@link CaseEvents#payload}
     */
    public void publish(PunishmentEntity punishment, CaseChanges.Appended event, CaseActor by, long now, Map<String, Object> change) {
        try {
            this.publisher.publish(CaseEvents.routingKey(event.type()),
                    CaseEvents.payload(event.identifier(), punishment.getIdentifier(), punishment.getOwner(), event.seq(), by, now, change));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not publish {} for punishment {}", event.type(), punishment.getIdentifier(), e);
        }
    }
}
