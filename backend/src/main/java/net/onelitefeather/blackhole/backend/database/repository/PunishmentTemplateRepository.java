package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;
import net.onelitefeather.blackhole.backend.database.models.PunishmentTemplateEntity;

import java.util.UUID;

@Repository
public interface PunishmentTemplateRepository extends CrudRepository<PunishmentTemplateEntity, UUID> {
}
