package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.EloProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileId;

@Repository
public interface EloProfileRepository extends PageableRepository<EloProfileEntity, PunishmentProfileId> {
}
