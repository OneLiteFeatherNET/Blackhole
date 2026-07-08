package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;

@Repository
public interface PunishmentProfileRepository extends PageableRepository<PunishmentProfileEntity, String> {

    /**
     * Finds profiles that currently have at least one active punishment (ban or chat ban).
     * Used by the scheduled expiry sweep so it doesn't have to scan every profile in the system.
     */
    Page<PunishmentProfileEntity> findByActiveBanIsNotNullOrActiveChatBanIsNotNull(Pageable pageable);
}
