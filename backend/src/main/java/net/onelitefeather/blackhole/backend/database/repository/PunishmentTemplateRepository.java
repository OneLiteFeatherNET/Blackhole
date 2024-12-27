package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.UUID;

@Repository
public interface PunishmentTemplateRepository extends CrudRepository<PunishmentTemplateRepository, UUID> {
}
