package net.onelitefeather.blackhole.backend.security;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.dto.StaffActorDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Erasure of staff identity data. Anonymizing clears the display name and keeps the {@code oid}, so
 * history still points at the same principal; once the Entra account is deleted the oid no longer
 * resolves to a person. The action is audited with the admin's own verified {@code oid}.
 */
@Singleton
public class StaffActorService {

    private static final Logger LOG = LoggerFactory.getLogger(StaffActorService.class);

    static final String ANONYMIZED_EVENT = "staff-actor.anonymized";

    private final StaffActorRepository actors;
    private final DomainEventPublisher events;

    public StaffActorService(StaffActorRepository actors, DomainEventPublisher events) {
        this.actors = actors;
        this.events = events;
    }

    /**
     * Clears the display name of {@code oid}. Repeating it is harmless.
     *
     * @param admin the verified caller, recorded in the log and the {@code staff-actor.anonymized} event
     * @return the {@link StaffActorDTO.Response}, or a {@link StaffActorDTO.Error} (404) for an unknown principal
     */
    public StaffActorDTO anonymize(UUID oid, Actor admin) {
        Optional<StaffActorEntity> found = this.actors.findById(oid);
        if (found.isEmpty()) {
            return new StaffActorDTO.Error("there is no staff actor " + oid, ErrorKind.NOT_FOUND);
        }
        StaffActorEntity actor = found.get();
        actor.setDisplayName(null);
        this.actors.update(actor);
        LOG.info("Staff actor {} anonymized by {}", oid, admin.oid());
        this.events.publish(ANONYMIZED_EVENT, Map.of("oid", oid.toString(), "anonymizedBy", admin.oid().toString()));
        return new StaffActorDTO.Response(oid);
    }
}
