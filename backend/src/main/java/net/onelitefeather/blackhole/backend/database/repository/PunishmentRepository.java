package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;

@Repository
public interface PunishmentRepository extends PageableRepository<PunishmentEntity, String> {
}
