package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PunishmentTemplateRepository extends PageableRepository<PunishmentTemplateEntity, UUID> {

    Optional<PunishmentTemplateEntity> findByReasonAndType(String reason, PunishType type);
}
