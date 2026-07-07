package net.onelitefeather.blackhole.backend.database.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.repository.PageableRepository;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.dto.PunishType;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PunishmentTemplateRepository extends PageableRepository<PunishmentTemplateEntity, UUID> {

    Page<PunishmentTemplateEntity> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<PunishmentTemplateEntity> findByTenantIdAndReasonAndType(UUID tenantId, String reason, PunishType type);
}
