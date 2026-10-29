package net.onelitefeather.blackhole.backend.exemption;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;

@Repository
public interface PlayerExemptionRepository extends PageableRepository<PlayerExemptionEntity, PlayerExemptionId> {

    /** Every exemption row of one owner (at most one per source). */
    List<PlayerExemptionEntity> findByIdOwner(String owner);
}
