package net.onelitefeather.blackhole.backend.integration;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Persistence of the integration registry. The service decides; the store only reads and writes rows. */
public interface IntegrationStore {

    /** A stored registration with its audit timestamps. */
    record Stored(long id, IntegrationSpec spec, long createdAt, long updatedAt) {
    }

    Optional<Stored> find(long id);

    /** Every registration, ordered by source system. */
    List<Stored> list();

    /**
     * Inserts a registration.
     *
     * @return the new id, or empty when the source system or the Entra client id is already registered
     */
    OptionalLong insert(IntegrationSpec spec, UUID actorOid, long now);

    /**
     * Replaces the editable fields of a registration. The source system and the client id are not changed.
     *
     * @return {@code false} when there is no registration with that id
     */
    boolean update(long id, IntegrationSpec spec, UUID actorOid, long now);
}
