package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;

@Repository
public interface PunishmentProfileRepository extends PageableRepository<PunishmentProfileEntity, String> {

    /**
     * Finds profiles that currently have at least one active punishment in any section.
     * Used by the scheduled expiry sweep so it doesn't have to scan every profile in the system.
     */
    @Query(
            value = "SELECT p FROM PunishmentProfileEntity p WHERE p.active IS NOT EMPTY ORDER BY p.owner",
            countQuery = "SELECT COUNT(p) FROM PunishmentProfileEntity p WHERE p.active IS NOT EMPTY"
    )
    Page<PunishmentProfileEntity> findWithActivePunishments(Pageable pageable);
}
