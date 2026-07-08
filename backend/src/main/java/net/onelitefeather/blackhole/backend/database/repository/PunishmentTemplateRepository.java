package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.dto.PunishType;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PunishmentTemplateRepository extends PageableRepository<PunishmentTemplateEntity, UUID> {

    Optional<PunishmentTemplateEntity> findByReasonAndType(String reason, PunishType type);
}
