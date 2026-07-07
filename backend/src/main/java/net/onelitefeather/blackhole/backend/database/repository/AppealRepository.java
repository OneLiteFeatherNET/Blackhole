package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.AppealEntity;
import net.onelitefeather.blackhole.backend.dto.AppealStatus;

import java.util.List;
import java.util.UUID;

@Repository
public interface AppealRepository extends PageableRepository<AppealEntity, UUID> {

    Page<AppealEntity> findByTenantId(UUID tenantId, Pageable pageable);

    /**
     * Prior appeals for the same punishment - used for the repeat-appeal eligibility check.
     */
    List<AppealEntity> findByPunishmentIdentifierAndStatusIn(String punishmentIdentifier, List<AppealStatus> statuses);
}
