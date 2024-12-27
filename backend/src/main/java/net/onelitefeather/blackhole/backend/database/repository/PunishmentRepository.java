package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import net.onelitefeather.blackhole.backend.database.models.PunishmentEntity;

@Repository
public interface PunishmentRepository extends CrudRepository<PunishmentEntity, String> {
}
