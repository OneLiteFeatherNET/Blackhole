package net.onelitefeather.blackhole.backend.appeal;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AppealRepository extends PageableRepository<AppealEntity, UUID> {

    /**
     * Prior appeals for the same punishment - used for the repeat-appeal eligibility check.
     */
    List<AppealEntity> findByPunishmentIdentifierAndStatusIn(String punishmentIdentifier, List<AppealStatus> statuses);
}
