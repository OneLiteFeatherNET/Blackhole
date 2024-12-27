package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.repository.CrudRepository;
import io.micronaut.data.annotation.Repository;
import net.onelitefeather.blackhole.backend.database.models.PunishmentProfileEntity;

@Repository
public interface PunishmentProfileRepository extends CrudRepository<PunishmentProfileEntity, String> {
}
